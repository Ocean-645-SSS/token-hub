package com.tokenhub.service;

import com.tokenhub.common.*;
import com.tokenhub.entity.Order;
import com.tokenhub.mapper.OrderMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class StockReleaseServiceTest {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final OrderService normal = mock(OrderService.class);
    private final SeckillReservationService recovery = mock(SeckillReservationService.class);
    private final StockReleaseService service = new StockReleaseService(orders, normal, recovery);
    private void order(OrderType type, OrderStatus status) {
        Order order = new Order(); order.setOrderType(type); order.setStatus(status);
        when(orders.findByOrderNo("ORD1")).thenReturn(order);
    }
    @Test void normalReleaseRetainsItsTransactionProxy() {
        order(OrderType.NORMAL, OrderStatus.USER_CANCELLED); service.release("ORD1");
        verify(normal).releaseStock("ORD1"); verifyNoInteractions(recovery);
    }
    @Test void seckillReleaseUsesDurableTransactionAndNoRedisIncrement() {
        order(OrderType.SECKILL, OrderStatus.TIMEOUT_CANCELLED); service.release("ORD1");
        verify(recovery).releaseOrder("ORD1"); verifyNoInteractions(normal);
    }
    @Test void paidOrderCannotReleaseStock() {
        order(OrderType.SECKILL, OrderStatus.PAID); service.release("ORD1");
        verifyNoInteractions(recovery, normal);
    }
}
