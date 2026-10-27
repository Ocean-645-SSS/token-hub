package com.tokenhub.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.common.BusinessException;
import com.tokenhub.common.OrderStatus;
import com.tokenhub.entity.LocalMessage;
import com.tokenhub.entity.Order;
import com.tokenhub.entity.SeckillReservation;
import com.tokenhub.mapper.*;
import com.tokenhub.messaging.SeckillOrderMessage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

@Service
public class SeckillReservationService {
    private final SeckillReservationMapper reservations;
    private final SeckillInventoryMapper inventory;
    private final SeckillActivityMapper activities;
    private final OrderMapper orders;
    private final LocalMessageMapper messages;
    private final ObjectMapper json;

    public SeckillReservationService(SeckillReservationMapper reservations, SeckillInventoryMapper inventory,
            SeckillActivityMapper activities, OrderMapper orders, LocalMessageMapper messages, ObjectMapper json) {
        this.reservations = reservations; this.inventory = inventory; this.activities = activities;
        this.orders = orders; this.messages = messages; this.json = json;
    }

    @Transactional
    public void reserve(SeckillOrderMessage event) {
        var activity = activities.findById(event.activityId());
        var now = LocalDateTime.now(ZoneOffset.UTC);
        if (activity == null || !Integer.valueOf(1).equals(activity.getStatus())
                || !activity.getPackageId().equals(event.packageId())
                || now.isBefore(activity.getStartTime()) || now.isAfter(activity.getEndTime())) {
            throw new BusinessException("SECKILL_UNAVAILABLE", "activity is unavailable", HttpStatus.CONFLICT);
        }
        SeckillReservation reservation = new SeckillReservation();
        reservation.setRequestId(event.requestId()); reservation.setUserId(event.userId());
        reservation.setActivityId(event.activityId()); reservation.setPackageId(event.packageId());
        // The unique user/activity key also survives loss of Redis's participation set.
        if (reservations.insert(reservation) != 1) throw new IllegalStateException("reservation insert failed");
        inventory.initialize(event.activityId());
        if (inventory.reserve(event.activityId()) != 1)
            throw new BusinessException("SECKILL_OUT_OF_STOCK", "seckill is out of stock", HttpStatus.CONFLICT);
        enqueue("SECKILL_ORDER", event.requestId(), "seckill.order", encode(event));
        snapshot(event.activityId());
    }

    @Transactional
    public void compensate(String requestId) {
        SeckillReservation reservation = reservations.lock(requestId);
        if (reservation == null || !"RESERVED".equals(reservation.getState())) return;
        Order order = orders.findByRequestId(requestId);
        if (order != null) {
            reservations.transition(requestId, "ORDER_CREATED", order.getOrderNo());
            return;
        }
        scheduleRelease(requestId);
    }

    // Caller must hold the reservation row lock in its transaction.
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void scheduleRelease(String requestId) {
        reservations.transition(requestId, "COMPENSATION_PENDING", null);
        enqueue("SECKILL_RESERVATION_RELEASE", requestId, "seckill.reservation.release", requestId);
    }

    @Transactional
    public void releaseReservation(String requestId) {
        SeckillReservation reservation = reservations.lock(requestId);
        if (reservation == null || !"COMPENSATION_PENDING".equals(reservation.getState())) return;
        release(reservation);
    }

    @Transactional
    public void releaseOrder(String orderNo) {
        Order order = orders.findByOrderNo(orderNo);
        if (order == null || order.getOrderType() != com.tokenhub.common.OrderType.SECKILL
                || (order.getStatus() != OrderStatus.USER_CANCELLED
                && order.getStatus() != OrderStatus.TIMEOUT_CANCELLED)) return;
        SeckillReservation reservation = reservations.lock(order.getRequestId());
        if (reservation == null) throw new IllegalStateException("missing durable reservation for " + orderNo);
        if ("ORDER_CREATED".equals(reservation.getState()) && orderNo.equals(reservation.getOrderNo())) {
            release(reservation);
        } else if (!"RELEASED".equals(reservation.getState())) {
            throw new IllegalStateException("reservation does not belong to cancelled order");
        }
        messages.markSent("SECKILL_STOCK_RELEASE", orderNo);
    }

    private void release(SeckillReservation reservation) {
        if (inventory.release(reservation.getActivityId()) != 1)
            throw new IllegalStateException("missing inventory ledger");
        reservations.transition(reservation.getRequestId(), "RELEASED", reservation.getOrderNo());
        snapshot(reservation.getActivityId());
    }

    private void snapshot(long activityId) {
        enqueue("SECKILL_INVENTORY_SYNC", UUID.randomUUID().toString(), "seckill.inventory", Long.toString(activityId));
    }

    private void enqueue(String type, String bizId, String topic, String payload) {
        LocalMessage message = new LocalMessage();
        message.setMessageId(UUID.randomUUID().toString()); message.setEventType(type);
        message.setBizId(bizId); message.setTopic(topic); message.setPayload(payload);
        if (messages.insert(message) != 1) throw new IllegalStateException("outbox insert failed");
    }

    private String encode(Object event) {
        try { return json.writeValueAsString(event); }
        catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
    }
}
