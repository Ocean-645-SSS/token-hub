package com.tokenhub.controller;
import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.*;
import com.tokenhub.service.ApiKeyService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/api-keys") public class ApiKeyController { private final ApiKeyService service; public ApiKeyController(ApiKeyService service){this.service=service;} @PostMapping public ApiResponse<ApiKeyResponse> create(@Valid @RequestBody CreateApiKeyRequest request){return ApiResponse.ok(service.create(request));} @GetMapping public ApiResponse<List<ApiKeyResponse>> list(){return ApiResponse.ok(service.list());} @DeleteMapping("/{id}") public ApiResponse<Void> revoke(@PathVariable long id){service.revoke(id);return ApiResponse.ok(null);} }
