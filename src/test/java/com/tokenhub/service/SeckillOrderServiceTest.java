package com.tokenhub.service;

import com.tokenhub.mapper.*;
import com.tokenhub.entity.*;
import com.tokenhub.messaging.SeckillOrderMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillOrderServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final SeckillActivityMapper activities = mock(SeckillActivityMapper.class);
    private final TokenPackageMapper packages = mock(TokenPackageMapper.class);
    private final LocalMessageMapper messages = mock(LocalMessageMapper.class);
    private final SeckillOrderService service = new SeckillOrderService(redis, orders, activities, packages, messages);
    private final SeckillOrderMessage message = new SeckillOrderMessage("req1", 7L, 2L, 3L);
    private void validReservation() {
        when(redis.opsForHash().get("seckill:reservation:req1", "status")).thenReturn("PENDING");
        SeckillActivity activity = new SeckillActivity(); activity.setPackageId(3L);
        TokenPackage tokenPackage = new TokenPackage(); tokenPackage.setId(3L);
        when(activities.findById(2L)).thenReturn(activity); when(packages.findById(3L)).thenReturn(tokenPackage);
        when(orders.insertSeckill(any())).thenReturn(1); when(messages.insert(any())).thenReturn(1);
    }
    @Test void releasedReservationCannotCreateLateOrder() {
        when(redis.opsForHash().get("seckill:reservation:req1", "status")).thenReturn("RELEASED");
        service.create(message); verifyNoInteractions(orders, activities, packages, messages);
    }
    @Test void firstCreationWritesTimeoutAndReplayDoesNotWriteAnother() {
        validReservation(); service.create(message);
        var captor = org.mockito.ArgumentCaptor.forClass(Order.class);
        verify(orders).insertSeckill(captor.capture()); Order created = captor.getValue();
        verify(messages).insert(argThat(m -> m.getEventType().equals("ORDER_TIMEOUT")
                && m.getTopic().equals("order.delay") && m.getBizId().equals(created.getOrderNo())
                && m.getPayload().equals(created.getOrderNo())));
        when(orders.findByRequestId("req1")).thenReturn(created);
        service.create(message); verify(messages, times(1)).insert(any()); verify(orders, times(1)).insertSeckill(any());
    }
    @Test void outboxFailureDoesNotAdvertiseCreatedOrder() {
        validReservation(); when(messages.insert(any())).thenReturn(0);
        assertThrows(IllegalStateException.class, () -> service.create(message));
        verify(redis.opsForHash(), never()).putAll(anyString(), anyMap());
    }
    @Test void rollbackNeverPublishesOrderResult() {
        validReservation(); TransactionSynchronizationManager.initSynchronization();
        try {
            service.create(message);
            for (var callback : TransactionSynchronizationManager.getSynchronizations())
                callback.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            verify(redis.opsForHash(), never()).putAll(anyString(), anyMap());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
    @Test void concurrentDuplicateRollsBackForFreshMqRetry() {
        validReservation();
        when(orders.insertSeckill(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
        assertThrows(org.springframework.dao.DuplicateKeyException.class, () -> service.create(message));
        verifyNoInteractions(messages); verify(redis.opsForHash(), never()).putAll(anyString(), anyMap());
    }
    @Test void resultIsPublishedOnlyAfterCommit() {
        validReservation(); TransactionSynchronizationManager.initSynchronization();
        try {
            service.create(message);
            verify(redis.opsForHash(), never()).putAll(anyString(), anyMap());
            for (var callback : TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
            verify(redis.opsForHash()).putAll(eq("seckill:reservation:req1"), anyMap());
        } finally { TransactionSynchronizationManager.clearSynchronization(); }
    }
}
