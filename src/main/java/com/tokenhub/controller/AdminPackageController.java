package com.tokenhub.controller;

import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.CreatePackageRequest;
import com.tokenhub.dto.UpdatePackageRequest;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.service.AdminPackageService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/packages")
public class AdminPackageController {
    private final AdminPackageService service;

    public AdminPackageController(AdminPackageService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TokenPackage> create(@Valid @RequestBody CreatePackageRequest request) {
        return ApiResponse.success(service.create(request));
    }

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<TokenPackage> update(@PathVariable long id, @Valid @RequestBody UpdatePackageRequest request) {
        return ApiResponse.success(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<Void> deactivate(@PathVariable long id) {
        service.deactivate(id);
        return ApiResponse.success(null);
    }
}
