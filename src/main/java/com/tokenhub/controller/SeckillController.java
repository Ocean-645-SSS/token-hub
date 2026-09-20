package com.tokenhub.controller;

import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.SeckillReservationResponse;
import com.tokenhub.dto.SeckillResultResponse;
import com.tokenhub.service.SeckillService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/seckill") public class SeckillController {
    private final SeckillService service;
    public SeckillController(SeckillService service) { this.service = service; }
    @PostMapping("/{activityId}") public ApiResponse<SeckillReservationResponse> reserve(@PathVariable long activityId) { return ApiResponse.success(service.reserve(activityId)); }
    @GetMapping("/result/{requestId}") public ApiResponse<SeckillResultResponse> result(@PathVariable String requestId) { return ApiResponse.success(service.result(requestId)); }
}
