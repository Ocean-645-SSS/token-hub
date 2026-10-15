package com.tokenhub.service;
import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.PageResponse;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.TokenPackageMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.util.List;
@Service public class PackageService {
    private final TokenPackageMapper mapper; private final PackageSearchService searchService;
    public PackageService(TokenPackageMapper mapper, PackageSearchService searchService) { this.mapper = mapper; this.searchService = searchService; }
    public PageResponse<TokenPackage> list(int page, int size) { return searchService.search(null, null, null, null, null, null, page, size); }
    public TokenPackage detail(long id) { TokenPackage item = mapper.findById(id); if (item == null) throw new BusinessException("PACKAGE_NOT_FOUND", "package not found", HttpStatus.NOT_FOUND); return item; }
    public PageResponse<TokenPackage> search(String keyword, String modelCode, BigDecimal minPrice, BigDecimal maxPrice, Long minTokens, Long maxTokens, int page, int size) { return searchService.search(keyword, modelCode, minPrice, maxPrice, minTokens, maxTokens, page, size); }
}
