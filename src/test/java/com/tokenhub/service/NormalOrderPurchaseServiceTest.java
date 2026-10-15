package com.tokenhub.service;

import com.tokenhub.common.*;
import com.tokenhub.dto.CreateOrderRequest;
import com.tokenhub.entity.Order;
import com.tokenhub.mapper.OrderMapper;
import com.tokenhub.security.CurrentUser;
import org.junit.jupiter.api.*;
import org.springframework.dao.DuplicateKeyException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NormalOrderPurchaseServiceTest {
    private final OrderMapper orders = mock(OrderMapper.class);
    private final OrderService creator = mock(OrderService.class);
    private final NormalOrderPurchaseService service = new NormalOrderPurchaseService(orders, creator);
    @BeforeEach void login() { CurrentUser.set(7L); }
    @AfterEach void logout() { CurrentUser.clear(); }
    private Order existing() {
        Order order = new Order(); order.setId(9L); order.setOrderNo("ORD1");
        order.setUserId(7L); order.setPackageId(3L); order.setOrderType(OrderType.NORMAL);
        order.setStatus(OrderStatus.USER_CANCELLED); return order;
    }
    @Test void replayReturnsOriginalEvenIfCancelledWithoutCreatingAnything() {
        when(orders.findByPurchaseKey(7L, "key1")).thenReturn(existing());
        var result = service.create(new CreateOrderRequest(3L), "key1");
        assertEquals(9L, result.id()); assertEquals(OrderStatus.USER_CANCELLED, result.status());
        verifyNoInteractions(creator);
    }
    @Test void sameKeyDifferentPackageIsRejected() {
        when(orders.findByPurchaseKey(7L, "key1")).thenReturn(existing());
        assertEquals("IDEMPOTENCY_KEY_CONFLICT", assertThrows(BusinessException.class,
            () -> service.create(new CreateOrderRequest(4L), "key1")).code());
        verifyNoInteractions(creator);
    }
    @Test void concurrentDuplicateInsertReplaysWinnerAfterTransactionalCallReturns() {
        var request = new CreateOrderRequest(3L);
        when(orders.findByPurchaseKey(7L, "key1")).thenReturn(null, existing());
        when(creator.create(request, 7L, "key1")).thenThrow(new DuplicateKeyException("duplicate"));
        assertEquals("ORD1", service.create(request, "key1").orderNo());
        var sequence = inOrder(orders, creator);
        sequence.verify(orders).findByPurchaseKey(7L, "key1");
        sequence.verify(creator).create(request, 7L, "key1");
        sequence.verify(orders).findByPurchaseKey(7L, "key1");
    }
    @Test void unrelatedDuplicateKeyIsNotMasked() {
        var request = new CreateOrderRequest(3L);
        when(creator.create(request, 7L, "key1")).thenThrow(new DuplicateKeyException("order number"));
        assertThrows(DuplicateKeyException.class, () -> service.create(request, "key1"));
    }
    @Test void invalidKeyIsRejectedBeforeDatabaseAccess() {
        for (String key : new String[]{"", "bad key", "x".repeat(65), "中文"}) {
            assertThrows(BusinessException.class, () -> service.create(new CreateOrderRequest(3L), key));
        }
        verifyNoInteractions(orders, creator);
    }
    @Test void newKeysCreateIndependentPurchases() {
        var request = new CreateOrderRequest(3L);
        service.create(request, "key1"); service.create(request, "key2");
        verify(creator).create(request, 7L, "key1"); verify(creator).create(request, 7L, "key2");
    }
}
