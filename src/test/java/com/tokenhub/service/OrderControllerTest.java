package com.tokenhub.service;

import com.tokenhub.controller.OrderController;
import com.tokenhub.dto.CreateOrderRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

class OrderControllerTest {
    @Test void missingKeyIsRejectedAndValidKeyReachesPurchaseService() throws Exception {
        OrderService orders=mock(OrderService.class);
        NormalOrderPurchaseService purchases=mock(NormalOrderPurchaseService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new OrderController(orders,purchases)).build();
        mvc.perform(post("/api/orders").contentType("application/json").content("{\"packageId\":3}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(purchases);
        mvc.perform(post("/api/orders").header("Idempotency-Key","key1")
                .contentType("application/json").content("{\"packageId\":3}"))
                .andExpect(status().isOk());
        verify(purchases).create(new CreateOrderRequest(3L),"key1");
    }
    @Test void cancelDoesNotDirectlyReleaseNormalStock() throws Exception {
        OrderService orders=mock(OrderService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new OrderController(orders,mock(NormalOrderPurchaseService.class))).build();
        mvc.perform(post("/api/orders/9/cancel")).andExpect(status().isOk());
        verify(orders).cancel(9L); verifyNoMoreInteractions(orders);
    }
}
