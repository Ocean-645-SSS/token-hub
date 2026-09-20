package com.tokenhub.controller;

import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.AiRecommendRequest;
import com.tokenhub.dto.AiRecommendResponse;
import com.tokenhub.service.AiGuideService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AiGuideController {
    private final AiGuideService service;
    public AiGuideController(AiGuideService service) { this.service = service; }
    @PostMapping("/ai/recommend")
    public ApiResponse<AiRecommendResponse> recommend(@Valid @RequestBody AiRecommendRequest request) {
        return ApiResponse.success(service.recommend(request));
    }
}
