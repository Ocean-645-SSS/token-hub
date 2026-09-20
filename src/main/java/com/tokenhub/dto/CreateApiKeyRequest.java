package com.tokenhub.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
public record CreateApiKeyRequest(@NotBlank String name, @NotEmpty List<@NotBlank String> allowedModels) { }
