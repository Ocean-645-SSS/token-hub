package com.tokenhub.entity;
import com.tokenhub.common.ModelRequestStatus;
import com.tokenhub.common.ProviderCostStatus;
import lombok.Data;
import java.time.LocalDateTime;
@Data public class ModelRequest { private Long id; private String requestId; private Long userId; private Long apiKeyId; private String modelCode; private Long inputTokens; private Long outputTokens; private Long totalTokens; private Long reservedTokens; private ModelRequestStatus status; private Integer retryCount; private ProviderCostStatus providerCostStatus; private String resultReference; private LocalDateTime createdAt; private LocalDateTime finishedAt; }
