package com.glmapper.coding.http.api.dto;

import jakarta.validation.constraints.NotBlank;

public record SetThinkingLevelRequest(@NotBlank String level) {
}
