package com.tokenhub.controller;

import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.CreateOrderRequest;
import com.tokenhub.dto.OrderResponse;
import com.tokenhub.service.OrderService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/orders") public class OrderController {
    private final OrderService service;
    public OrderController(OrderService service) { this.service = service; }
    @PostMapping public ApiResponse<OrderResponse> create(@Valid @RequestBody CreateOrderRequest request) { return ApiResponse.success(service.create(request)); }
    @PostMapping("/{id}/pay") public ApiResponse<Void> pay(@PathVariable long id) { service.pay(id); return ApiResponse.success(null); }
    @PostMapping("/{id}/cancel") public ApiResponse<Void> cancel(@PathVariable long id) { service.cancel(id); service.processStockRelease(service.detail(id).orderNo()); return ApiResponse.success(null); }
    @GetMapping("/{id}") public ApiResponse<OrderResponse> detail(@PathVariable long id) { return ApiResponse.success(service.detail(id)); }
}
