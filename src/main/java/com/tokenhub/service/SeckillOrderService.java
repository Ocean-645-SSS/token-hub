package com.tokenhub.service;

import com.tokenhub.common.OrderStatus;
import com.tokenhub.common.OrderType;
import com.tokenhub.entity.Order;
import com.tokenhub.entity.LocalMessage;
import com.tokenhub.mapper.LocalMessageMapper;
import com.tokenhub.entity.SeckillActivity;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.OrderMapper;
import com.tokenhub.mapper.SeckillActivityMapper;
import com.tokenhub.mapper.TokenPackageMapper;
import com.tokenhub.messaging.SeckillOrderMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.tokenhub.profiling.SeckillTrace;
import java.util.UUID;

@Service public class SeckillOrderService {
    private final StringRedisTemplate redis; private final OrderMapper orders; private final SeckillActivityMapper activities; private final TokenPackageMapper packages; private final LocalMessageMapper messages;
    private final com.tokenhub.mapper.SeckillReservationMapper reservations;
    private final SeckillReservationService recovery;
    public SeckillOrderService(StringRedisTemplate redis, OrderMapper orders, SeckillActivityMapper activities, TokenPackageMapper packages, LocalMessageMapper messages,
            com.tokenhub.mapper.SeckillReservationMapper reservations, SeckillReservationService recovery) {
        this.redis = redis; this.orders = orders; this.activities = activities; this.packages = packages; this.messages = messages;
        this.reservations = reservations; this.recovery = recovery;
    }
    @Transactional public void create(SeckillOrderMessage message) {
        String key = "seckill:reservation:" + message.requestId();
        var reservation = reservations.lock(message.requestId());
        SeckillTrace.mark(message.requestId(), "t9");
        if (reservation == null) throw new IllegalStateException("missing durable reservation");
        if (!java.util.Objects.equals(reservation.getUserId(), message.userId())
                || !java.util.Objects.equals(reservation.getActivityId(), message.activityId())
                || !java.util.Objects.equals(reservation.getPackageId(), message.packageId()))
            throw new IllegalArgumentException("reservation/event mismatch");
        if (!"RESERVED".equals(reservation.getState()) && !"ORDER_CREATED".equals(reservation.getState())) return;
        Order existing = orders.findByRequestId(message.requestId());
        if (existing != null) {
            reservations.transition(message.requestId(), "ORDER_CREATED", existing.getOrderNo());
            complete(key, existing.getOrderNo(), message.requestId()); return;
        }
        if (!"RESERVED".equals(reservation.getState())) throw new IllegalStateException("order missing for completed reservation");
        SeckillActivity activity = activities.findById(message.activityId()); TokenPackage tokenPackage = packages.findById(message.packageId());
        if (activity == null || tokenPackage == null || !activity.getPackageId().equals(message.packageId())) {
            recovery.scheduleRelease(message.requestId()); return;
        }
        Order order = new Order(); order.setOrderNo("ORD" + UUID.randomUUID().toString().replace("-", "").substring(0, 29));
        order.setUserId(message.userId()); order.setPackageId(message.packageId()); order.setOrderType(OrderType.SECKILL); order.setSeckillActivityId(message.activityId()); order.setRequestId(message.requestId());
        order.setOriginalPrice(tokenPackage.getPrice()); order.setPayPrice(activity.getSeckillPrice()); order.setTokenAmount(tokenPackage.getTokenAmount()); order.setStatus(OrderStatus.WAIT_PAY);
        // A concurrent duplicate rolls back and is retried by MQ in a fresh transaction.
        if (orders.insertSeckill(order) != 1) throw new IllegalStateException("order insert failed");
        SeckillTrace.mark(message.requestId(), "t10");
        LocalMessage timeout = new LocalMessage();
        timeout.setMessageId(UUID.randomUUID().toString().replace("-", ""));
        timeout.setEventType("ORDER_TIMEOUT"); timeout.setBizId(order.getOrderNo());
        timeout.setTopic("order.delay"); timeout.setPayload(order.getOrderNo());
        if (messages.insert(timeout) != 1) throw new IllegalStateException("outbox insert failed");
        reservations.transition(message.requestId(), "ORDER_CREATED", order.getOrderNo());
        complete(key, order.getOrderNo(), message.requestId());
    }
    private void complete(String key, String orderNo, String requestId) {
        Runnable publishResult = () -> {
            try { redis.opsForHash().putAll(key, java.util.Map.of("status", "ORDER_CREATED", "orderNo", orderNo)); }
            catch (RuntimeException exception) {
                org.slf4j.LoggerFactory.getLogger(SeckillOrderService.class).warn("result cache update failed for {}", requestId, exception);
            }
            SeckillTrace.mark(requestId, "t11");
            SeckillTrace.finish(requestId);
        };
        // Do not advertise an order that could still roll back with its timeout event.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publishResult.run(); }
            });
        } else publishResult.run();
    }
}
