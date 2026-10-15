package com.tokenhub.service;

import com.tokenhub.common.BusinessException;
import com.tokenhub.common.OrderStatus;
import com.tokenhub.dto.CreateOrderRequest;
import com.tokenhub.entity.Order;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.LocalMessageMapper;
import com.tokenhub.mapper.OrderMapper;
import com.tokenhub.mapper.TokenPackageMapper;
import com.tokenhub.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderServiceTest {
    private final TokenPackageMapper packages = mock(TokenPackageMapper.class);
    private final OrderMapper orders = mock(OrderMapper.class);
    private final LocalMessageMapper messages = mock(LocalMessageMapper.class);
    private final TokenGrantHandler grants = mock(TokenGrantHandler.class);
    private final OrderService service = new OrderService(packages, orders, messages, grants);
    @AfterEach void clearUser() { CurrentUser.clear(); }

    @Test void createClaimsPurchaseKeyBeforeStockAndTimeoutEvent() {
        CurrentUser.set(7L); TokenPackage tokenPackage = packageOf(3L);
        when(packages.findById(3L)).thenReturn(tokenPackage); when(packages.decreaseStock(3L)).thenReturn(1); when(messages.insert(any())).thenReturn(1);
        doAnswer(invocation -> { invocation.<Order>getArgument(0).setId(9L); return 1; }).when(orders).insert(any(Order.class));
        var result = service.create(new CreateOrderRequest(3L), 7L, "purchase-1");
        assertEquals(OrderStatus.WAIT_PAY, result.status()); assertEquals("NORMAL", result.orderType().name());
        assertTrue(result.orderNo().startsWith("ORD")); assertEquals(32, result.orderNo().length());
        var sequence = inOrder(orders, packages, messages);
        sequence.verify(orders).insert(argThat(o -> "purchase-1".equals(o.getPurchaseKey())));
        sequence.verify(packages).decreaseStock(3L);
        sequence.verify(messages).insert(argThat(m -> m.getEventType().equals("ORDER_TIMEOUT")));
        verify(orders).insert(argThat(order -> order.getStatus() == OrderStatus.WAIT_PAY && order.getUserId() == 7L));
    }

    @Test void noStockThrowsForTransactionRollback() {
        CurrentUser.set(7L); when(packages.findById(3L)).thenReturn(packageOf(3L)); when(packages.decreaseStock(3L)).thenReturn(0); when(orders.insert(any())).thenReturn(1);
        BusinessException error = assertThrows(BusinessException.class, () -> service.create(new CreateOrderRequest(3L), 7L, "purchase-1"));
        assertEquals("PACKAGE_OUT_OF_STOCK", error.code()); verify(messages, never()).insert(any());
    }

    @Test void payAndCancelUseExpectedOldStateAndCreateTheirSingleEvents() {
        when(messages.insert(any())).thenReturn(1); CurrentUser.set(7L); Order order = orderOf(9L, "ORD123"); when(orders.findById(9L)).thenReturn(order);
        when(orders.pay(9L, 7L, OrderStatus.WAIT_PAY, OrderStatus.PAID)).thenReturn(1);
        service.pay(9L);
        verify(messages).insert(argThat(message -> message.getEventType().equals("TOKEN_GRANT") && message.getBizId().equals("ORD123")));
        when(orders.transitionForUser(9L, 7L, OrderStatus.WAIT_PAY, OrderStatus.USER_CANCELLED)).thenReturn(1);
        service.cancel(9L);
        verify(messages).insert(argThat(message -> message.getEventType().equals("NORMAL_ORDER_STOCK_RELEASE") && message.getBizId().equals("ORD123")));
    }

    @Test void paymentOrCancellationStateConflictDoesNotCreateEvent() {
        CurrentUser.set(7L); when(orders.findById(9L)).thenReturn(orderOf(9L, "ORD123"));
        assertEquals("ORDER_STATE_CONFLICT", assertThrows(BusinessException.class, () -> service.pay(9L)).code());
        verify(messages, never()).insert(any());
    }

    @Test void seckillCancelWritesOnlySeckillReleaseEvent() {
        CurrentUser.set(7L); Order order = orderOf(9L, "ORD123");
        order.setOrderType(com.tokenhub.common.OrderType.SECKILL);
        when(orders.findById(9L)).thenReturn(order);
        when(orders.transitionForUser(9L, 7L, OrderStatus.WAIT_PAY, OrderStatus.USER_CANCELLED)).thenReturn(1, 0);
        when(messages.insert(any())).thenReturn(1);
        service.cancel(9L);
        assertThrows(BusinessException.class, () -> service.cancel(9L));
        verify(messages, times(1)).insert(argThat(m -> m.getEventType().equals("SECKILL_STOCK_RELEASE")));
        verifyNoInteractions(packages);
    }

    private TokenPackage packageOf(long id) { TokenPackage value = new TokenPackage(); value.setId(id); value.setPrice(new BigDecimal("19.90")); value.setTokenAmount(1000L); return value; }
    private Order orderOf(long id, String orderNo) { Order value = new Order(); value.setId(id); value.setOrderNo(orderNo); value.setUserId(7L); value.setPackageId(3L); value.setStatus(OrderStatus.WAIT_PAY); value.setOrderType(com.tokenhub.common.OrderType.NORMAL); return value; }
}
