package com.tokenhub.entity;

import com.tokenhub.common.OrderStatus;
import com.tokenhub.common.OrderType;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data public class Order {
    private Long id; private String orderNo; private Long userId; private Long packageId;
    private OrderType orderType; private Long seckillActivityId; private String requestId; private String purchaseKey;
    private BigDecimal originalPrice; private BigDecimal payPrice; private Long tokenAmount;
    private OrderStatus status; private LocalDateTime createdAt; private LocalDateTime paidAt;
    private LocalDateTime completedAt; private LocalDateTime expiredAt; private LocalDateTime updatedAt;
}
