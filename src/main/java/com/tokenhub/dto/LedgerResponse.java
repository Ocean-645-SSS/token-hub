package com.tokenhub.dto;

import com.tokenhub.common.LedgerType;
import java.time.LocalDateTime;

public record LedgerResponse(String bizId, String modelCode, LedgerType type, Long amount, Long balanceBefore, Long balanceAfter, String remark, LocalDateTime createdAt) { }
