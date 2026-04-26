package com.glmapper.coding.http.api.controller;

public record RuntimeIdentity(
        String tenantId,
        String namespace,
        String userId
) {
}

