package com.tokenhub.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.PackageSearchCriteria;
import com.tokenhub.dto.PageResponse;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.TokenPackageMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;

@Service public class PackageSearchService {
    private static final String INDEX = "token_package_index";
    private final TokenPackageMapper packages; private final ObjectMapper json; private final HttpClient http = HttpClient.newHttpClient(); private final String baseUrl;
    public PackageSearchService(TokenPackageMapper packages, ObjectMapper json, @Value("${tokenhub.elasticsearch.url}") String baseUrl) { this.packages=packages; this.json=json; this.baseUrl=baseUrl; }
    @EventListener(ApplicationReadyEvent.class) public void synchronizeOnStartup() { synchronize(); }
    public void synchronize() { try { request("PUT", "/" + INDEX, "{\"mappings\":{\"properties\":{\"id\":{\"type\":\"long\"},\"name\":{\"type\":\"text\",\"fields\":{\"keyword\":{\"type\":\"keyword\"}}},\"modelCode\":{\"type\":\"keyword\"},\"tokenAmount\":{\"type\":\"long\"},\"price\":{\"type\":\"scaled_float\",\"scaling_factor\":100},\"description\":{\"type\":\"text\"},\"scenarios\":{\"type\":\"text\"}}}}"); for(TokenPackage p:packages.findAllActive()) request("PUT", "/"+INDEX+"/_doc/"+p.getId(), json.writeValueAsString(document(p))); request("POST", "/" + INDEX + "/_refresh", null); } catch(Exception e) { throw unavailable(e); } }
    public PageResponse<TokenPackage> search(String keyword, String modelCode, BigDecimal minPrice, BigDecimal maxPrice, Long minTokens, Long maxTokens, int page, int size) {
        try { List<Object> filters=new ArrayList<>(); if(modelCode!=null&&!modelCode.isBlank()) filters.add(Map.of("term",Map.of("modelCode",modelCode))); if(minPrice!=null||maxPrice!=null) filters.add(Map.of("range",Map.of("price",range(minPrice,maxPrice)))); if(minTokens!=null||maxTokens!=null) filters.add(Map.of("range",Map.of("tokenAmount",range(minTokens,maxTokens)))); Map<String,Object> bool=new LinkedHashMap<>(); bool.put("filter",filters); if(keyword!=null&&!keyword.isBlank()) bool.put("must",List.of(Map.of("multi_match",Map.of("query",keyword,"fields",List.of("name","description","scenarios"))))); Map<String,Object> body=Map.of("from",(page-1)*size,"size",size,"query",Map.of("bool",bool)); Map<String,Object> response=json.readValue(request("POST","/"+INDEX+"/_search",json.writeValueAsString(body)),new TypeReference<>(){}); Map<String,Object> hits=(Map<String,Object>)response.get("hits"); Map<String,Object> total=(Map<String,Object>)hits.get("total"); List<Map<String,Object>> rows=(List<Map<String,Object>>)hits.get("hits"); List<TokenPackage> data=rows.stream().map(row->token((Map<String,Object>)row.get("_source"))).toList(); return new PageResponse<>(data,page,size,((Number)total.get("value")).longValue()); } catch(Exception e) { throw unavailable(e); }
    }
    /** Candidate lookup used by AI guide. Results always originate from Elasticsearch. */
    public List<TokenPackage> searchPackages(PackageSearchCriteria criteria) {
        return search(criteria.keyword(), criteria.modelCode(), criteria.minPrice(), criteria.maxPrice(),
                criteria.minTokens(), criteria.maxTokens(), 1, 20).records();
    }
    private Map<String,Object> document(TokenPackage p){Map<String,Object> document=new LinkedHashMap<>();document.put("id",p.getId());document.put("name",p.getName());document.put("modelCode",p.getModelCode());document.put("tokenAmount",p.getTokenAmount());document.put("price",p.getPrice());document.put("description",p.getDescription());document.put("scenarios",p.getSuitableScenario());return document;}
    private Map<String,Object> range(Object min,Object max){Map<String,Object> r=new LinkedHashMap<>();if(min!=null)r.put("gte",min);if(max!=null)r.put("lte",max);return r;}
    private TokenPackage token(Map<String,Object> s){TokenPackage p=new TokenPackage();p.setId(((Number)s.get("id")).longValue());p.setName((String)s.get("name"));p.setModelCode((String)s.get("modelCode"));p.setTokenAmount(((Number)s.get("tokenAmount")).longValue());p.setPrice(new BigDecimal(s.get("price").toString()));p.setDescription((String)s.get("description"));p.setSuitableScenario((String)s.get("scenarios"));return p;}
    private String request(String method,String path,String body)throws Exception{HttpRequest.BodyPublisher publisher=body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body);HttpRequest r=HttpRequest.newBuilder(URI.create(baseUrl+path)).header("Content-Type","application/json").method(method,publisher).build();HttpResponse<String> res=http.send(r,HttpResponse.BodyHandlers.ofString());if(res.statusCode()>=300 && !(res.statusCode()==400 && res.body().contains("resource_already_exists_exception")))throw new IllegalStateException(res.body());return res.body();}
    private BusinessException unavailable(Exception e){return new BusinessException("SEARCH_UNAVAILABLE","package search is temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);}
}
