package com.tokenhub.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.common.ApiResponse;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import java.io.IOException;

@Component
public class JwtAuthenticationInterceptor implements HandlerInterceptor {
    private final JwtService jwtService; private final ObjectMapper objectMapper;
    public JwtAuthenticationInterceptor(JwtService jwtService, ObjectMapper objectMapper) { this.jwtService = jwtService; this.objectMapper = objectMapper; }
    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return reject(response);
        try { CurrentUser.set(jwtService.parseUserId(header.substring(7))); return true; } catch (JwtException | IllegalArgumentException exception) { return reject(response); }
    }
    @Override public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception exception) { CurrentUser.clear(); }
    private boolean reject(HttpServletResponse response) throws IOException { response.setStatus(401); response.setContentType("application/json"); objectMapper.writeValue(response.getWriter(), ApiResponse.error("UNAUTHORIZED", "valid access token required")); return false; }
}
