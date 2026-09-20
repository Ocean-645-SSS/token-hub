package com.tokenhub.dto;

import com.tokenhub.common.OrderStatus;
import com.tokenhub.common.OrderType;
import java.math.BigDecimal;

public record OrderResponse(Long id, String orderNo, Long packageId, OrderType orderType, BigDecimal originalPrice, BigDecimal payPrice, Long tokenAmount, OrderStatus status) { }
