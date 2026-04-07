package com.glmapper.coding.http.api.dto;

import jakarta.validation.constraints.NotBlank;

public record NavigateTreeRequest(@NotBlank String entryId) {
}
