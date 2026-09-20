package com.tokenhub.service;

import com.tokenhub.common.OrderStatus;
import com.tokenhub.common.OrderType;
import com.tokenhub.entity.Order;
import com.tokenhub.mapper.*;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderTimeoutTest {
    @Test void onlyWaitPayOrderSchedulesOneReleaseAfterTimeoutTransition() {
        TokenPackageMapper packages=mock(TokenPackageMapper.class); OrderMapper orders=mock(OrderMapper.class); LocalMessageMapper messages=mock(LocalMessageMapper.class); TokenGrantHandler grants=mock(TokenGrantHandler.class); OrderService service=new OrderService(packages,orders,messages,grants);
        Order waitPay=order(OrderStatus.WAIT_PAY); when(orders.findByOrderNo("ORD1")).thenReturn(waitPay); when(orders.timeout(9L,OrderStatus.WAIT_PAY,OrderStatus.TIMEOUT_CANCELLED)).thenReturn(1,0);
        service.timeout("ORD1"); service.timeout("ORD1");
        verify(messages,times(1)).insert(argThat(m->m.getEventType().equals("NORMAL_ORDER_STOCK_RELEASE")&&m.getBizId().equals("ORD1")));
    }
    @Test void paidOrderCannotBeTimedOutOrRestocked() {
        TokenPackageMapper packages=mock(TokenPackageMapper.class); OrderMapper orders=mock(OrderMapper.class); LocalMessageMapper messages=mock(LocalMessageMapper.class); TokenGrantHandler grants=mock(TokenGrantHandler.class); OrderService service=new OrderService(packages,orders,messages,grants);
        when(orders.findByOrderNo("ORD1")).thenReturn(order(OrderStatus.PAID)); when(orders.timeout(9L,OrderStatus.WAIT_PAY,OrderStatus.TIMEOUT_CANCELLED)).thenReturn(0);
        service.timeout("ORD1"); verify(messages,never()).insert(any()); verify(packages,never()).increaseStock(anyLong());
    }
    private static Order order(OrderStatus status){Order o=new Order();o.setId(9L);o.setOrderNo("ORD1");o.setOrderType(OrderType.NORMAL);o.setStatus(status);return o;}
}
