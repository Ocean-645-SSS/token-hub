package com.tokenhub.service;

import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.AiRecommendRequest;
import com.tokenhub.dto.AiRecommendResponse;
import com.tokenhub.dto.ChatCompletionRequest;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.model.ModelProvider;
import com.tokenhub.model.ModelProviderException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiGuideService {
    private static final int DEFAULT_DAYS = 30;
    private final PackageSearchService packages;
    private final ModelProvider provider;

    public AiGuideService(PackageSearchService packages, ModelProvider provider) {
        this.packages = packages;
        this.provider = provider;
    }

    public AiRecommendResponse recommend(AiRecommendRequest request) {
        long estimated = estimateTokenUsage(request.dailyRequests(), request.inputTokensPerRequest(),
                request.outputTokensPerRequest(), request.days());
        List<TokenPackage> candidates = packages.searchPackages(request.requirement(), request.budget(), estimated);
        String assumption = usageAssumption(request, estimated);
        if (candidates.isEmpty()) {
            return new AiRecommendResponse(request.requirement(), estimated, assumption, List.of(),
                    "No matching package was found in Elasticsearch candidates.");
        }
        try {
            ChatCompletionRequest completion = new ChatCompletionRequest(candidates.get(0).getModelCode(), List.of(Map.of(
                    "role", "user", "content", prompt(request.requirement(), request.budget(), assumption, candidates))), 512, false);
            ModelProvider.ProviderResponse response = provider.complete(completion);
            return new AiRecommendResponse(request.requirement(), estimated, assumption, candidates, responseText(response.body()));
        } catch (ModelProviderException e) {
            throw new BusinessException("AI_GUIDE_UNAVAILABLE", "AI guide is temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    public long estimateTokenUsage(Integer dailyRequests, Long inputTokens, Long outputTokens, Integer days) {
        if (dailyRequests == null || inputTokens == null || outputTokens == null) return 0L;
        return Math.multiplyExact(Math.multiplyExact(dailyRequests.longValue(), inputTokens + outputTokens),
                days == null ? DEFAULT_DAYS : days.longValue());
    }

    private String usageAssumption(AiRecommendRequest request, long estimated) {
        if (estimated == 0) return "Usage was not supplied; monthly Token usage cannot be estimated.";
        int days = request.days() == null ? DEFAULT_DAYS : request.days();
        return "%d requests/day × (%d input + %d output tokens) × %d days = %d tokens/month".formatted(
                request.dailyRequests(), request.inputTokensPerRequest(), request.outputTokensPerRequest(), days, estimated);
    }

    private String prompt(String requirement, Object budget, String assumption, List<TokenPackage> candidates) {
        List<Map<String, Object>> safeCandidates = candidates.stream().map(p -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", p.getId()); row.put("name", p.getName()); row.put("modelCode", p.getModelCode());
            row.put("tokenAmount", p.getTokenAmount()); row.put("price", p.getPrice());
            row.put("description", p.getDescription()); row.put("scenarios", p.getSuitableScenario());
            return row;
        }).toList();
        return "Recommend only from these Elasticsearch candidates. Explain purpose, budget and usage assumptions, then give reasons. "
                + "Requirement: " + requirement + "; Budget: " + budget + "; Usage: " + assumption + "; Candidates: " + safeCandidates;
    }

    @SuppressWarnings("unchecked")
    private String responseText(Map<String, Object> body) {
        Object choices = body.get("choices");
        if (choices instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> choice) {
            Object message = ((Map<String, Object>) choice).get("message");
            if (message instanceof Map<?, ?> map && ((Map<String, Object>) map).get("content") != null) return String.valueOf(((Map<String, Object>) map).get("content"));
        }
        return "The model returned no recommendation text.";
    }
}
