package com.tokenhub.dto;

import com.tokenhub.entity.TokenPackage;
import java.util.List;

public record AiRecommendResponse(
        String purpose,
        long estimatedMonthlyTokens,
        String usageAssumption,
        PackageSearchCriteria criteria,
        List<TokenPackage> candidates,
        String recommendation) { }
