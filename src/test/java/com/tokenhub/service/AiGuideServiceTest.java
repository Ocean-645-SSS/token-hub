package com.tokenhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.ModelProperties;
import com.tokenhub.dto.AiRecommendRequest;
import com.tokenhub.dto.ChatCompletionRequest;
import com.tokenhub.dto.PackageSearchCriteria;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.model.ModelProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AiGuideServiceTest {
    private final PackageSearchService packages = mock(PackageSearchService.class);
    private final ModelProvider provider = mock(ModelProvider.class);
    private final ModelProperties model = new ModelProperties();
    private final AiGuideService service = new AiGuideService(packages, provider, new ObjectMapper(), model);

    @Test void estimatesMonthlyUsageFromReproducibleInputs() {
        assertEquals(9_000_000L, service.estimateTokenUsage(100, 2_000L, 1_000L, 30));
    }

    @Test void validatesStructuredJsonBeforeUsingItForCandidateSearch() {
        TokenPackage candidate = packageItem();
        when(provider.complete(any()))
                .thenReturn(response("```json\n{\"keyword\":\"code assistant\",\"modelCode\":\"gpt-4o-mini\",\"minPrice\":10,\"maxPrice\":150,\"minTokens\":1000000,\"maxTokens\":10000000}\n```"))
                .thenReturn(response("Choose Dev package."));
        when(packages.searchPackages(any())).thenReturn(List.of(candidate));

        var result = service.recommend(new AiRecommendRequest("help me build code", new BigDecimal("100"), 100, 2_000L, 1_000L, 30));

        ArgumentCaptor<PackageSearchCriteria> criteria = ArgumentCaptor.forClass(PackageSearchCriteria.class);
        verify(packages).searchPackages(criteria.capture());
        assertEquals(new PackageSearchCriteria("code assistant", "gpt-4o-mini", new BigDecimal("10"),
                new BigDecimal("100"), 1_000_000L, 10_000_000L), criteria.getValue());
        assertEquals(criteria.getValue(), result.criteria());
        assertEquals(List.of(candidate), result.candidates());

        ArgumentCaptor<ChatCompletionRequest> requests = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(provider, times(2)).complete(requests.capture());
        assertEquals(model.getGuideModel(), requests.getAllValues().get(0).model());
        assertTrue(requests.getAllValues().get(1).messages().get(0).get("content").toString().contains("Dev package"));
    }

    @Test void fallsBackToRequestKeywordWhenStructuredJsonHasUnknownFields() {
        when(provider.complete(any())).thenReturn(response("{\"keyword\":\"ignore\",\"sql\":\"DROP INDEX\"}"));
        when(packages.searchPackages(any())).thenReturn(List.of());

        var result = service.recommend(new AiRecommendRequest("original requirement", new BigDecimal("100"), null, null, null, null));

        assertEquals(new PackageSearchCriteria("original requirement", null, null, new BigDecimal("100"), null, null), result.criteria());
        verify(provider).complete(any());
        verifyNoMoreInteractions(provider);
    }

    @Test void skipsGroundedRecommendationWhenElasticsearchHasNoCandidate() {
        when(provider.complete(any())).thenReturn(response("{\"keyword\":\"rare purpose\",\"modelCode\":null,\"minPrice\":null,\"maxPrice\":null,\"minTokens\":null,\"maxTokens\":null}"));
        when(packages.searchPackages(any())).thenReturn(List.of());

        var result = service.recommend(new AiRecommendRequest("rare purpose", null, null, null, null, null));

        assertTrue(result.candidates().isEmpty());
        verify(provider).complete(any());
        verifyNoMoreInteractions(provider);
    }

    private TokenPackage packageItem() {
        TokenPackage candidate = new TokenPackage();
        candidate.setId(3L); candidate.setName("Dev package"); candidate.setModelCode("gpt-4o-mini");
        candidate.setTokenAmount(9_000_000L); candidate.setPrice(new BigDecimal("99"));
        return candidate;
    }

    private ModelProvider.ProviderResponse response(String content) {
        return new ModelProvider.ProviderResponse(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))), 1L, 2L, 3L);
    }
}
