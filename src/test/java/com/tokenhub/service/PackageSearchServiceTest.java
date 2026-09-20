package com.tokenhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.dto.PageResponse;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.TokenPackageMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PackageSearchServiceTest {
    @Test void guideCandidateLookupUsesPurposeAndBudgetThroughElasticsearchSearch() {
        TokenPackage candidate = new TokenPackage(); candidate.setId(7L);
        PackageSearchService service = spy(new PackageSearchService(mock(TokenPackageMapper.class), new ObjectMapper(), "http://localhost:9200"));
        doReturn(new PageResponse<>(List.of(candidate), 1, 20, 1)).when(service)
                .search("code assistant", null, null, new BigDecimal("100"), null, null, 1, 20);
        assertEquals(List.of(candidate), service.searchPackages("code assistant", new BigDecimal("100"), 9_000_000L));
    }
}
