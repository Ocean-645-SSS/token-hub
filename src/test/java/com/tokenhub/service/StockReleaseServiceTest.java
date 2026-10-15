package com.tokenhub.service;

import com.tokenhub.common.*;
import com.tokenhub.entity.Order;
import com.tokenhub.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StockReleaseServiceTest {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final LocalMessageMapper messages = mock(LocalMessageMapper.class);
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final OrderService normal = mock(OrderService.class);
    private final StockReleaseService service = new StockReleaseService(orders, messages, redis, normal);
    private void cancelled(OrderType type) {
        Order order = new Order(); order.setOrderType(type); order.setStatus(OrderStatus.USER_CANCELLED);
        order.setSeckillActivityId(2L); when(orders.findByOrderNo("ORD1")).thenReturn(order);
        when(messages.countPendingEvent("SECKILL_STOCK_RELEASE", "ORD1")).thenReturn(1);
    }
    @Test void normalReleaseCallsTransactionProxyDirectly() {
        cancelled(OrderType.NORMAL); service.release("ORD1");
        verify(normal).releaseStock("ORD1"); verifyNoInteractions(redis);
    }
    @Test void redisFailureKeepsEventPendingForRetry() {
        cancelled(OrderType.SECKILL);
        when(redis.execute(any(RedisScript.class), anyList())).thenThrow(new IllegalStateException("redis down"));
        assertThrows(IllegalStateException.class, () -> service.release("ORD1"));
        verify(messages, never()).markSent(anyString(), anyString()); verifyNoInteractions(normal);
    }
    @Test void luaReplayStillCompletesPendingEvent() {
        cancelled(OrderType.SECKILL);
        when(redis.execute(any(RedisScript.class), anyList())).thenReturn(0L);
        service.release("ORD1");
        var sequence = inOrder(redis, messages);
        sequence.verify(messages).countPendingEvent("SECKILL_STOCK_RELEASE", "ORD1");
        sequence.verify(redis).execute(any(RedisScript.class), eq(java.util.List.of("release:ORD1", "seckill:stock:2")));
        sequence.verify(messages).markSent("SECKILL_STOCK_RELEASE", "ORD1");
    }
}
