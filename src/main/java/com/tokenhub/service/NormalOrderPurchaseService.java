package com.tokenhub.service;

import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.CreateOrderRequest;
import com.tokenhub.dto.OrderResponse;
import com.tokenhub.entity.Order;
import com.tokenhub.mapper.OrderMapper;
import com.tokenhub.security.CurrentUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class NormalOrderPurchaseService {
    private final OrderMapper orders;
    private final OrderService creator;
    public NormalOrderPurchaseService(OrderMapper orders, OrderService creator) {
        this.orders = orders; this.creator = creator;
    }
    // Deliberately outside a transaction: duplicate inserts must roll back before replay lookup.
    public OrderResponse create(CreateOrderRequest request, String key) {
        Long userId = CurrentUser.get();
        if (userId == null) throw new BusinessException("UNAUTHORIZED", "valid access token required", HttpStatus.UNAUTHORIZED);
        if (key == null || !key.matches("[A-Za-z0-9_-]{1,64}"))
            throw new BusinessException("INVALID_IDEMPOTENCY_KEY", "Idempotency-Key must contain 1-64 letters, digits, underscores or hyphens", HttpStatus.BAD_REQUEST);
        Order existing = orders.findByPurchaseKey(userId, key);
        if (existing != null) return replay(existing, request);
        try {
            return creator.create(request, userId, key);
        } catch (DuplicateKeyException exception) {
            Order winner = orders.findByPurchaseKey(userId, key);
            if (winner == null) throw exception;
            return replay(winner, request);
        }
    }
    private OrderResponse replay(Order order, CreateOrderRequest request) {
        if (!order.getPackageId().equals(request.packageId()))
            throw new BusinessException("IDEMPOTENCY_KEY_CONFLICT", "key already used for a different package", HttpStatus.CONFLICT);
        return new OrderResponse(order.getId(), order.getOrderNo(), order.getPackageId(), order.getOrderType(),
                order.getOriginalPrice(), order.getPayPrice(), order.getTokenAmount(), order.getStatus());
    }
}
