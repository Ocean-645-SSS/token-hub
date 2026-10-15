package com.tokenhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.config.ModelProperties;
import com.tokenhub.dto.AiRecommendRequest;
import com.tokenhub.dto.ChatCompletionRequest;
import com.tokenhub.dto.PackageSearchCriteria;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.model.ModelProvider;
import com.tokenhub.model.ModelProviderException;
import com.tokenhub.mapper.TokenPackageMapper;
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
    private final TokenPackageMapper catalog = mock(TokenPackageMapper.class);
    private final ModelProvider provider = mock(ModelProvider.class);
    private final ModelProperties model = new ModelProperties();
    private final AiGuideService service = new AiGuideService(packages, catalog, provider, new ObjectMapper(), model);

    @Test void estimatesMonthlyUsageFromReproducibleInputs() {
        assertEquals(9_000_000L, service.estimateTokenUsage(100, 2_000L, 1_000L, 30));
    }

    @Test void validatesStructuredJsonBeforeUsingItForCandidateSearch() {
        TokenPackage candidate = packageItem();
        when(catalog.findActiveModelCode("gpt-4o-mini")).thenReturn("gpt-4o-mini");
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

    @Test void retriesInvalidStructuredJsonWithValidationFeedbackThenUsesCorrectedCriteria() {
        TokenPackage candidate = packageItem();
        when(catalog.findActiveModelCode("gpt-4o-mini")).thenReturn("gpt-4o-mini");
        when(provider.complete(any()))
                .thenReturn(response("{\"keyword\":\"ignore\",\"sql\":\"DROP INDEX\"}"))
                .thenReturn(response("{\"keyword\":\"code assistant\",\"modelCode\":\"gpt-4o-mini\",\"minPrice\":null,\"maxPrice\":null,\"minTokens\":null,\"maxTokens\":null}"))
                .thenReturn(response("Recommend Dev package."));
        when(packages.searchPackages(any())).thenReturn(List.of(candidate));

        var result = service.recommend(new AiRecommendRequest("help with code", null, null, null, null, null));

        assertEquals(new PackageSearchCriteria("code assistant", "gpt-4o-mini", null, null, null, null), result.criteria());
        ArgumentCaptor<ChatCompletionRequest> requests = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(provider, times(3)).complete(requests.capture());
        assertTrue(requests.getAllValues().get(1).messages().get(0).get("content").toString()
                .contains("failed backend validation"));
    }

    @Test void fallsBackAfterThreeInvalidStructuredResponses() {
        when(provider.complete(any()))
                .thenReturn(response("not json"))
                .thenReturn(response("{\"keyword\":\"ignore\",\"unexpected\":true}"))
                .thenReturn(response("{\"keyword\":\"ignore\",\"modelCode\":\"bad/code\"}"));
        when(packages.searchPackages(any())).thenReturn(List.of());

        var result = service.recommend(new AiRecommendRequest("original requirement", new BigDecimal("100"), null, null, null, null));

        assertEquals(new PackageSearchCriteria("original requirement", null, null, new BigDecimal("100"), null, null), result.criteria());
        verify(provider, times(3)).complete(any());
        verifyNoMoreInteractions(provider);
    }

    @Test void doesNotRetryAValidClarificationResponse() {
        when(provider.complete(any())).thenReturn(response("{\"keyword\":null,\"modelCode\":null,\"minPrice\":null,\"maxPrice\":null,\"minTokens\":null,\"maxTokens\":null,\"clarificationQuestion\":\"What is your maximum budget?\"}"));

        var result = service.recommend(new AiRecommendRequest("cheap package", null, null, null, null, null));

        assertTrue(result.needsClarification());
        verify(provider).complete(any());
        verifyNoMoreInteractions(provider);
    }

    @Test void asksForClarificationWhenModelCodeHasNoActivePackage() {
        when(provider.complete(any())).thenReturn(response("{\"keyword\":\"code assistant\",\"modelCode\":\"gpt-999\",\"minPrice\":null,\"maxPrice\":null,\"minTokens\":null,\"maxTokens\":null}"));
        when(catalog.findActiveModelCode("gpt-999")).thenReturn(null);

        var result = service.recommend(new AiRecommendRequest("use gpt-999 for coding", null, null, null, null, null));

        assertTrue(result.needsClarification());
        assertEquals("gpt-999", result.criteria().modelCode());
        assertTrue(result.clarificationQuestion().contains("是否接受其他模型"));
        verify(provider).complete(any());
        verifyNoMoreInteractions(provider);
        verifyNoMoreInteractions(packages);
    }

    @Test void retriesTransientProviderErrorsWithinTheSameAttemptBudget() {
        TokenPackage candidate = packageItem();
        when(provider.complete(any()))
                .thenThrow(new ModelProviderException("temporary timeout", true, null))
                .thenReturn(response("{\"keyword\":\"code assistant\",\"modelCode\":null,\"minPrice\":null,\"maxPrice\":null,\"minTokens\":null,\"maxTokens\":null}"))
                .thenReturn(response("Recommend Dev package."));
        when(packages.searchPackages(any())).thenReturn(List.of(candidate));

        var result = service.recommend(new AiRecommendRequest("help with code", null, null, null, null, null));

        assertEquals("code assistant", result.criteria().keyword());
        verify(provider, times(3)).complete(any());
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
