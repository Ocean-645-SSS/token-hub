package com.tokenhub.controller;
import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.LoginRequest;
import com.tokenhub.dto.LoginResponse;
import com.tokenhub.service.AuthService;
import com.tokenhub.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.Map;
@RestController @RequestMapping("/api/auth") public class AuthController { private final AuthService service; public AuthController(AuthService service) { this.service = service; } @PostMapping("/login") public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) { return ApiResponse.success(service.login(request)); } @org.springframework.web.bind.annotation.GetMapping("/me") public ApiResponse<Map<String, Long>> me() { return ApiResponse.success(Map.of("userId", CurrentUser.get())); } }
