package com.glmapper.coding.http.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SetModelRequest(
        @NotBlank String provider,
        @NotBlank String modelId
) {
}
