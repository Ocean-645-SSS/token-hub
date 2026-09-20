package com.tokenhub.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data public class SeckillActivity {
    private Long id; private Long packageId; private BigDecimal seckillPrice; private Long stock;
    private LocalDateTime startTime; private LocalDateTime endTime; private Integer status;
}
