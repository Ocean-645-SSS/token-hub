package com.tokenhub.service;
import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.PageResponse;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.TokenPackageMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.List;
@Service public class PackageService {
    private final TokenPackageMapper mapper; public PackageService(TokenPackageMapper mapper) { this.mapper = mapper; }
    public PageResponse<TokenPackage> list(int page, int size) { return new PageResponse<>(mapper.list(size, offset(page, size)), page, size, mapper.count()); }
    public TokenPackage detail(long id) { TokenPackage item = mapper.findById(id); if (item == null) throw new BusinessException("PACKAGE_NOT_FOUND", "package not found", HttpStatus.NOT_FOUND); return item; }
    public PageResponse<TokenPackage> search(String keyword, String modelCode, int page, int size) { return new PageResponse<>(mapper.search(keyword, modelCode, size, offset(page, size)), page, size, mapper.searchCount(keyword, modelCode)); }
    private long offset(int page, int size) { return (long) (page - 1) * size; }
}
