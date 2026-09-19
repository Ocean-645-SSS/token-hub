package com.tokenhub.controller;
import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.PageResponse;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.service.PackageService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
@RestController @RequestMapping("/api/packages") public class PackageController {
    private final PackageService service; public PackageController(PackageService service) { this.service = service; }
    @GetMapping public ApiResponse<PageResponse<TokenPackage>> list(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) { return ApiResponse.success(service.list(validPage(page), validSize(size))); }
    @GetMapping("/search") public ApiResponse<PageResponse<TokenPackage>> search(@RequestParam(required=false) String keyword, @RequestParam(required=false) String modelCode, @RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) { return ApiResponse.success(service.search(keyword, modelCode, validPage(page), validSize(size))); }
    @GetMapping("/{id}") public ApiResponse<TokenPackage> detail(@PathVariable long id) { return ApiResponse.success(service.detail(id)); }
    private int validPage(int value) { return Math.max(value, 1); } private int validSize(int value) { return Math.min(Math.max(value, 1), 100); }
}
