package com.tokenhub.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpdatePackageRequest(
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,64}") String modelCode,
        @NotNull @Positive Long tokenAmount,
        @NotNull @DecimalMin("0.00") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotBlank @Size(max = 500) String description,
        @Size(max = 500) String suitableScenario,
        @NotNull @Min(0) @Max(1) Integer status) { }
