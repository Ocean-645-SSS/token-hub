package com.tokenhub.common;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
@RestControllerAdvice(basePackages = "com.tokenhub")
public class GlobalExceptionHandler {
    @ExceptionHandler(BusinessException.class) public ResponseEntity<ApiResponse<Void>> business(BusinessException e) { return ResponseEntity.status(e.status()).body(ApiResponse.error(e.code(), e.getMessage())); }
    @ExceptionHandler(Exception.class) public ResponseEntity<ApiResponse<Void>> unexpected(Exception e) { return ResponseEntity.internalServerError().body(ApiResponse.error("INTERNAL_ERROR", "internal server error")); }
}
