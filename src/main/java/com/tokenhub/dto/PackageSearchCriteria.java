package com.tokenhub.dto;

import java.math.BigDecimal;

/** Server-validated filters used to build an Elasticsearch package query. */
public record PackageSearchCriteria(
        String keyword,
        String modelCode,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        Long minTokens,
        Long maxTokens) { }
