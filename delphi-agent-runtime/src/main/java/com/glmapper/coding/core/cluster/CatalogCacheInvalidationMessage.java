package com.glmapper.coding.core.cluster;

import java.time.Instant;

public record CatalogCacheInvalidationMessage(
        String scope,
        String namespace,
        String reason,
        String sourceNodeId,
        Instant timestamp
) {
    public static final String SCOPE_ALL = "ALL";
    public static final String SCOPE_NAMESPACE = "NAMESPACE";
}
