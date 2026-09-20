package com.tokenhub.service;

import com.tokenhub.dto.AiRecommendRequest;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.model.ModelProvider;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AiGuideServiceTest {
    private final PackageSearchService packages = mock(PackageSearchService.class);
    private final ModelProvider provider = mock(ModelProvider.class);
    private final AiGuideService service = new AiGuideService(packages, provider);

    @Test void estimatesMonthlyUsageFromReproducibleInputs() {
        assertEquals(9_000_000L, service.estimateTokenUsage(100, 2_000L, 1_000L, 30));
    }

    @Test void recommendsOnlyElasticsearchCandidates() {
        TokenPackage candidate = new TokenPackage(); candidate.setId(3L); candidate.setName("Dev package"); candidate.setModelCode("gpt-4o-mini"); candidate.setTokenAmount(9_000_000L); candidate.setPrice(new BigDecimal("99"));
        when(packages.searchPackages(eq("code assistant"), eq(new BigDecimal("100")), eq(9_000_000L))).thenReturn(List.of(candidate));
        when(provider.complete(any())).thenReturn(new ModelProvider.ProviderResponse(Map.of("choices", List.of(Map.of("message", Map.of("content", "Choose Dev package.")))), 1L, 2L, 3L));
        var result = service.recommend(new AiRecommendRequest("code assistant", new BigDecimal("100"), 100, 2_000L, 1_000L, 30));
        assertEquals(List.of(candidate), result.candidates()); assertEquals("Choose Dev package.", result.recommendation());
        verify(provider).complete(argThat(input -> input.messages().get(0).get("content").toString().contains("Dev package")));
    }

    @Test void skipsModelCallWhenElasticsearchHasNoCandidate() {
        when(packages.searchPackages(anyString(), isNull(), eq(0L))).thenReturn(List.of());
        var result = service.recommend(new AiRecommendRequest("rare purpose", null, null, null, null, null));
        assertTrue(result.candidates().isEmpty()); verifyNoInteractions(provider);
    }
}
