package com.glmapper.coding.http.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PromptRequest(@NotBlank String message) {
}
