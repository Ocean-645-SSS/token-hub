package com.tokenhub.controller;

import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.SeckillReservationResponse;
import com.tokenhub.dto.SeckillResultResponse;
import com.tokenhub.service.SeckillService;
import com.tokenhub.profiling.ResultApiTrace;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/seckill") public class SeckillController {
    private final SeckillService service;
    public SeckillController(SeckillService service) { this.service = service; }
    @PostMapping("/{activityId}") public ApiResponse<SeckillReservationResponse> reserve(@PathVariable long activityId) { return ApiResponse.success(service.reserve(activityId)); }
    @GetMapping("/result/{requestId}") public ApiResponse<SeckillResultResponse> result(
            @PathVariable String requestId,
            @RequestHeader(value = "X-TokenHub-Poll-Sequence", required = false) String pollSequence) {
        long r0Nanos = System.nanoTime();
        long r0WallMs = System.currentTimeMillis();
        long r1Nanos = System.nanoTime();
        SeckillResultResponse response = service.result(requestId);
        long r2Nanos = System.nanoTime();
        long r3Nanos = System.nanoTime();
        int sequence = parsePollSequence(pollSequence);
        ResultApiTrace.record(requestId, sequence, r0WallMs, r0Nanos, r1Nanos, r2Nanos, r3Nanos,
                200, response.result());
        return ApiResponse.success(response);
    }

    private int parsePollSequence(String value) {
        if (value == null || value.isBlank()) return -1;
        try { return Integer.parseInt(value); }
        catch (NumberFormatException ignored) { return -1; }
    }
}
