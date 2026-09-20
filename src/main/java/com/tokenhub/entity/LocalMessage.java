package com.tokenhub.entity;

import lombok.Data;

@Data public class LocalMessage {
    private Long id; private String messageId; private String eventType; private String bizId;
    private String topic; private String payload; private Integer status; private Integer retryCount;
}
