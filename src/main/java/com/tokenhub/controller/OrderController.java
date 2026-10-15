package com.tokenhub.controller;

import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.CreateOrderRequest;
import com.tokenhub.dto.OrderResponse;
import com.tokenhub.service.OrderService;
import com.tokenhub.service.NormalOrderPurchaseService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/orders") public class OrderController {
    private final OrderService service;
    private final NormalOrderPurchaseService purchases;
    public OrderController(OrderService service, NormalOrderPurchaseService purchases) { this.service = service; this.purchases = purchases; }
    @PostMapping public ApiResponse<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request, @RequestHeader("Idempotency-Key") String key) { return ApiResponse.success(purchases.create(request, key)); }
    @PostMapping("/{id}/pay") public ApiResponse<Void> pay(@PathVariable long id) { service.pay(id); return ApiResponse.success(null); }
    @PostMapping("/{id}/cancel") public ApiResponse<Void> cancel(@PathVariable long id) { service.cancel(id); return ApiResponse.success(null); }
    @GetMapping("/{id}") public ApiResponse<OrderResponse> detail(@PathVariable long id) { return ApiResponse.success(service.detail(id)); }
}
