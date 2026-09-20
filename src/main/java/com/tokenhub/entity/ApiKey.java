package com.tokenhub.entity;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class ApiKey { private Long id; private Long userId; private String name; private String keyPrefix; private String keyHash; private String allowedModels; private Integer status; private LocalDateTime createdAt; private LocalDateTime lastUsedAt; }
