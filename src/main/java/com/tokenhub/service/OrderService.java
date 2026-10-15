package com.tokenhub.service;

import com.tokenhub.common.BusinessException;
import com.tokenhub.common.OrderStatus;
import com.tokenhub.common.OrderType;
import com.tokenhub.dto.CreateOrderRequest;
import com.tokenhub.dto.OrderResponse;
import com.tokenhub.entity.LocalMessage;
import com.tokenhub.entity.Order;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.LocalMessageMapper;
import com.tokenhub.mapper.OrderMapper;
import com.tokenhub.mapper.TokenPackageMapper;
import com.tokenhub.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service public class OrderService {
    private final TokenPackageMapper packages; private final OrderMapper orders; private final LocalMessageMapper messages;
    private final TokenGrantHandler grants;
    public OrderService(TokenPackageMapper packages, OrderMapper orders, LocalMessageMapper messages, TokenGrantHandler grants) {
        this.packages = packages; this.orders = orders; this.messages = messages; this.grants = grants;
    }
    @Transactional public OrderResponse create(CreateOrderRequest request, long userId, String purchaseKey) {
        TokenPackage tokenPackage = packages.findById(request.packageId());
        if (tokenPackage == null) throw new BusinessException("PACKAGE_NOT_FOUND", "package not found", HttpStatus.NOT_FOUND);
        Order order = new Order();
        order.setOrderNo("ORD" + UUID.randomUUID().toString().replace("-", "").substring(0, 29));
        order.setUserId(userId); order.setPurchaseKey(purchaseKey); order.setPackageId(tokenPackage.getId()); order.setOrderType(OrderType.NORMAL);
        order.setOriginalPrice(tokenPackage.getPrice()); order.setPayPrice(tokenPackage.getPrice());
        order.setTokenAmount(tokenPackage.getTokenAmount()); order.setStatus(OrderStatus.WAIT_PAY);
        if (orders.insert(order) != 1) throw new IllegalStateException("order insert failed");
        if (packages.decreaseStock(tokenPackage.getId()) != 1) throw new BusinessException("PACKAGE_OUT_OF_STOCK", "package is out of stock", HttpStatus.CONFLICT);
        createMessage("ORDER_TIMEOUT", order.getOrderNo(), "order.delay", order.getOrderNo());
        return response(order);
    }
    @Transactional public void cancel(long id) {
        long userId = requireUser();
        Order order = ownedOrder(id, userId);
        if (orders.transitionForUser(id, userId, OrderStatus.WAIT_PAY, OrderStatus.USER_CANCELLED) != 1) throw stateConflict();
        createMessage(order.getOrderType() == OrderType.NORMAL ? "NORMAL_ORDER_STOCK_RELEASE" : "SECKILL_STOCK_RELEASE", order.getOrderNo(), "stock.release", order.getOrderNo());
    }
    @Transactional public void releaseStock(String orderNo) {
        Order order = orders.findByOrderNo(orderNo);
        if (order == null || order.getOrderType() != OrderType.NORMAL || (order.getStatus() != OrderStatus.USER_CANCELLED && order.getStatus() != OrderStatus.TIMEOUT_CANCELLED)) return;
        if (messages.markSent("NORMAL_ORDER_STOCK_RELEASE", orderNo) != 1) return;
        packages.increaseStock(order.getPackageId());
    }
    @Transactional public void pay(long id) {
        long userId = requireUser();
        Order order = ownedOrder(id, userId);
        if (orders.pay(id, userId, OrderStatus.WAIT_PAY, OrderStatus.PAID) != 1) throw stateConflict();
        createMessage("TOKEN_GRANT", order.getOrderNo(), "token.grant", order.getOrderNo());
    }
    @Transactional public void timeout(String orderNo) {
        Order order = orders.findByOrderNo(orderNo); if (order == null || orders.timeout(order.getId(), OrderStatus.WAIT_PAY, OrderStatus.TIMEOUT_CANCELLED) != 1) return;
        createMessage(order.getOrderType() == OrderType.NORMAL ? "NORMAL_ORDER_STOCK_RELEASE" : "SECKILL_STOCK_RELEASE", orderNo, "stock.release", orderNo);
    }
    public void processGrant(String orderNo) { grants.handle(orderNo); }
    public OrderResponse detail(long id) { return response(ownedOrder(id, requireUser())); }
    private Order ownedOrder(long id, long userId) {
        Order order = orders.findById(id);
        if (order == null || order.getUserId() != userId) throw new BusinessException("ORDER_NOT_FOUND", "order not found", HttpStatus.NOT_FOUND);
        return order;
    }
    private void createMessage(String eventType, String bizId, String topic, String payload) {
        LocalMessage message = new LocalMessage();
        message.setMessageId(UUID.randomUUID().toString().replace("-", "")); message.setEventType(eventType); message.setBizId(bizId);
        message.setTopic(topic); message.setPayload(payload);
        if (messages.insert(message) != 1) throw new IllegalStateException("outbox insert failed");
    }
    private long requireUser() { Long userId = CurrentUser.get(); if (userId == null) throw new BusinessException("UNAUTHORIZED", "valid access token required", HttpStatus.UNAUTHORIZED); return userId; }
    private BusinessException stateConflict() { return new BusinessException("ORDER_STATE_CONFLICT", "order state conflict", HttpStatus.CONFLICT); }
    private OrderResponse response(Order order) { return new OrderResponse(order.getId(), order.getOrderNo(), order.getPackageId(), order.getOrderType(), order.getOriginalPrice(), order.getPayPrice(), order.getTokenAmount(), order.getStatus()); }
}
