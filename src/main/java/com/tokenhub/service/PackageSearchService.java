package com.tokenhub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.PackageSearchCriteria;
import com.tokenhub.dto.PageResponse;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.TokenPackageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PackageSearchService {
    private static final Logger log = LoggerFactory.getLogger(PackageSearchService.class);
    private static final String INDEX = "token_package_index";
    private static final String MAPPING = "{\"mappings\":{\"properties\":{" +
            "\"id\":{\"type\":\"long\"},\"name\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}}," +
            "\"modelCode\":{\"type\":\"keyword\"},\"tokenAmount\":{\"type\":\"long\"}," +
            "\"price\":{\"type\":\"scaled_float\",\"scaling_factor\":100},\"description\":{\"type\":\"text\"}," +
            "\"scenarios\":{\"type\":\"text\"},\"active\":{\"type\":\"boolean\"}," +
            "\"catalogVersion\":{\"type\":\"long\"}}}}";

    private final TokenPackageMapper packages;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newHttpClient();
    private final String baseUrl;

    public PackageSearchService(TokenPackageMapper packages, ObjectMapper json,
                                @Value("${tokenhub.elasticsearch.url}") String baseUrl) {
        this.packages = packages;
        this.json = json;
        this.baseUrl = baseUrl;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void synchronizeOnStartup() {
        try {
            synchronize();
        } catch (RuntimeException exception) {
            log.error("Elasticsearch package-index startup sync failed; scheduled reconciliation will retry", exception);
        }
    }

    @Scheduled(fixedDelayString = "${tokenhub.package-index-sync.reconciliation-interval-ms:60000}")
    public void reconcilePeriodically() {
        try {
            synchronize();
        } catch (RuntimeException exception) {
            log.error("Elasticsearch package-index reconciliation failed; scheduled reconciliation will retry", exception);
        }
    }

    public synchronized void synchronize() {
        try {
            ensureIndex();
            List<TokenPackage> catalog = packages.findAllForIndexSync();
            Set<String> catalogIds = catalog.stream().map(item -> String.valueOf(item.getId())).collect(Collectors.toSet());
            int failures = 0;
            for (TokenPackage item : catalog) {
                try {
                    synchronizeDocument(item);
                } catch (Exception exception) {
                    failures++;
                    log.error("package index reconciliation failed for packageId={}", item.getId(), exception);
                }
            }
            try {
                tombstoneMissingDocuments(catalogIds);
            } catch (Exception exception) {
                failures++;
                log.error("package index stale-document reconciliation failed", exception);
            }
            request("POST", "/" + INDEX + "/_refresh", null);
            if (failures > 0) throw new IllegalStateException("package index reconciliation had " + failures + " failure(s)");
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    public void synchronizePackage(long packageId) {
        try {
            ensureIndex();
            TokenPackage item = packages.findByIdForIndexSync(packageId);
            if (item == null) {
                tombstoneMissingDocument(packageId);
            } else {
                synchronizeDocument(item);
            }
            request("POST", "/" + INDEX + "/_refresh", null);
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    public PageResponse<TokenPackage> search(String keyword, String modelCode, BigDecimal minPrice, BigDecimal maxPrice,
                                             Long minTokens, Long maxTokens, int page, int size) {
        try {
            List<Object> filters = new ArrayList<>();
            filters.add(Map.of("term", Map.of("active", true)));
            if (modelCode != null && !modelCode.isBlank()) filters.add(Map.of("term", Map.of("modelCode", modelCode)));
            if (minPrice != null || maxPrice != null) filters.add(Map.of("range", Map.of("price", range(minPrice, maxPrice))));
            if (minTokens != null || maxTokens != null) filters.add(Map.of("range", Map.of("tokenAmount", range(minTokens, maxTokens))));
            Map<String, Object> bool = new LinkedHashMap<>();
            bool.put("filter", filters);
            if (keyword != null && !keyword.isBlank()) {
                bool.put("must", List.of(Map.of("multi_match", Map.of("query", keyword, "fields", List.of("name", "description", "scenarios")))));
            }
            Map<String, Object> body = Map.of("from", (page - 1) * size, "size", size, "query", Map.of("bool", bool));
            Map<String, Object> response = json.readValue(request("POST", "/" + INDEX + "/_search", json.writeValueAsString(body)), new TypeReference<>() { });
            Map<String, Object> hits = (Map<String, Object>) response.get("hits");
            Map<String, Object> total = (Map<String, Object>) hits.get("total");
            List<Map<String, Object>> rows = (List<Map<String, Object>>) hits.get("hits");
            List<TokenPackage> data = rows.stream().map(row -> token((Map<String, Object>) row.get("_source"))).toList();
            return new PageResponse<>(data, page, size, ((Number) total.get("value")).longValue());
        } catch (Exception exception) {
            throw unavailable(exception);
        }
    }

    /** Candidate lookup used by AI guide. Results always originate from Elasticsearch. */
    public List<TokenPackage> searchPackages(PackageSearchCriteria criteria) {
        return search(criteria.keyword(), criteria.modelCode(), criteria.minPrice(), criteria.maxPrice(),
                criteria.minTokens(), criteria.maxTokens(), 1, 20).records();
    }

    private void ensureIndex() throws Exception {
        request("PUT", "/" + INDEX, MAPPING);
        request("PUT", "/" + INDEX + "/_mapping",
                "{\"properties\":{\"active\":{\"type\":\"boolean\"},\"catalogVersion\":{\"type\":\"long\"}}}");
    }

    private void synchronizeDocument(TokenPackage item) throws Exception {
        boolean active = Integer.valueOf(1).equals(item.getStatus());
        if (active) {
            versionedPut(item.getId(), item.getCatalogVersion(), document(item, true, item.getCatalogVersion()));
        } else {
            versionedPut(item.getId(), item.getCatalogVersion(), tombstone(item.getId(), item.getCatalogVersion()));
        }
    }

    private void versionedPut(long packageId, long version, Map<String, Object> document) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + INDEX + "/_doc/" + packageId
                        + "?version=" + version + "&version_type=external"))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(document)))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 409 && response.body().contains("version_conflict_engine_exception")) return;
        if (response.statusCode() >= 300) throw new IllegalStateException("Elasticsearch versioned write failed: " + response.body());
    }

    private void tombstoneMissingDocuments(Set<String> catalogIds) throws Exception {
        String page = request("POST", "/" + INDEX + "/_search?scroll=1m",
                "{\"size\":500,\"_source\":true,\"version\":true,\"query\":{\"match_all\":{}},\"sort\":[\"_doc\"]}");
        String scrollId = null;
        try {
            Map<String, Object> result = json.readValue(page, new TypeReference<>() { });
            scrollId = (String) result.get("_scroll_id");
            List<Map<String, Object>> rows = searchRows(result);
            while (!rows.isEmpty()) {
                for (Map<String, Object> row : rows) {
                    String documentId = String.valueOf(row.get("_id"));
                    if (!catalogIds.contains(documentId)) {
                        long version = ((Number) row.get("_version")).longValue() + 1;
                        versionedPut(Long.parseLong(documentId), version, tombstone(Long.parseLong(documentId), version));
                    }
                }
                if (scrollId == null) break;
                page = request("POST", "/_search/scroll", json.writeValueAsString(Map.of("scroll", "1m", "scroll_id", scrollId)));
                result = json.readValue(page, new TypeReference<>() { });
                scrollId = (String) result.get("_scroll_id");
                rows = searchRows(result);
            }
        } finally {
            if (scrollId != null) {
                try {
                    request("DELETE", "/_search/scroll", json.writeValueAsString(Map.of("scroll_id", List.of(scrollId))));
                } catch (Exception exception) {
                    log.warn("failed to clear Elasticsearch scroll context", exception);
                }
            }
        }
    }

    private void tombstoneMissingDocument(long packageId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/" + INDEX + "/_doc/" + packageId))
                .GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) return;
        if (response.statusCode() >= 300) throw new IllegalStateException("Elasticsearch document lookup failed: " + response.body());
        Map<String, Object> current = json.readValue(response.body(), new TypeReference<>() { });
        long nextVersion = ((Number) current.get("_version")).longValue() + 1;
        versionedPut(packageId, nextVersion, tombstone(packageId, nextVersion));
    }

    private Map<String, Object> tombstone(long packageId, long version) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", packageId);
        document.put("active", false);
        document.put("catalogVersion", version);
        return document;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> searchRows(Map<String, Object> response) {
        return (List<Map<String, Object>>) ((Map<String, Object>) response.get("hits")).get("hits");
    }

    private Map<String, Object> document(TokenPackage item, boolean active, long version) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("id", item.getId());
        document.put("name", item.getName());
        document.put("modelCode", item.getModelCode());
        document.put("tokenAmount", item.getTokenAmount());
        document.put("price", item.getPrice());
        document.put("description", item.getDescription());
        document.put("scenarios", item.getSuitableScenario());
        document.put("active", active);
        document.put("catalogVersion", version);
        return document;
    }

    private Map<String, Object> range(Object min, Object max) {
        Map<String, Object> range = new LinkedHashMap<>();
        if (min != null) range.put("gte", min);
        if (max != null) range.put("lte", max);
        return range;
    }

    private TokenPackage token(Map<String, Object> source) {
        TokenPackage item = new TokenPackage();
        item.setId(((Number) source.get("id")).longValue());
        item.setName((String) source.get("name"));
        item.setModelCode((String) source.get("modelCode"));
        item.setTokenAmount(((Number) source.get("tokenAmount")).longValue());
        item.setPrice(new BigDecimal(source.get("price").toString()));
        item.setDescription((String) source.get("description"));
        item.setSuitableScenario((String) source.get("scenarios"));
        if (source.get("catalogVersion") instanceof Number version) item.setCatalogVersion(version.longValue());
        return item;
    }

    private String request(String method, String path, String body) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .method(method, publisher)
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 300 && !(response.statusCode() == 400 && response.body().contains("resource_already_exists_exception"))) {
            throw new IllegalStateException(response.body());
        }
        return response.body();
    }

    private BusinessException unavailable(Exception exception) {
        return new BusinessException("SEARCH_UNAVAILABLE", "package search is temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
