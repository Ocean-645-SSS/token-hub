package com.tokenhub.entity;

import com.tokenhub.common.LedgerType;
import lombok.Data;
import java.time.LocalDateTime;

@Data public class TokenLedger {
    private Long id; private Long userId; private String modelCode; private String bizId;
    private LedgerType type; private Long amount; private Long balanceBefore; private Long balanceAfter;
    private String remark; private LocalDateTime createdAt;
}
