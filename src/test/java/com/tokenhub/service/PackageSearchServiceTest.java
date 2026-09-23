package com.tokenhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.dto.PackageSearchCriteria;
import com.tokenhub.dto.PageResponse;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.TokenPackageMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

class PackageSearchServiceTest {
    @Test void guideCandidateLookupDelegatesEveryValidatedCriteriaFieldToElasticsearchSearch() {
        TokenPackage candidate = new TokenPackage(); candidate.setId(7L);
        PackageSearchCriteria criteria = new PackageSearchCriteria("code assistant", "gpt-4o-mini", new BigDecimal("10"),
                new BigDecimal("100"), 1_000L, 2_000L);
        PackageSearchService service = spy(new PackageSearchService(mock(TokenPackageMapper.class), new ObjectMapper(), "http://localhost:9200"));
        doReturn(new PageResponse<>(List.of(candidate), 1, 20, 1)).when(service)
                .search("code assistant", "gpt-4o-mini", new BigDecimal("10"), new BigDecimal("100"), 1_000L, 2_000L, 1, 20);

        assertEquals(List.of(candidate), service.searchPackages(criteria));
    }
}
