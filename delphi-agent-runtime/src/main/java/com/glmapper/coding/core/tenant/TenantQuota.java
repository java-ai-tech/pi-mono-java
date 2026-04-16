package com.glmapper.coding.core.tenant;

/**
 * Resolved quota for a specific tenant/namespace.
 */
public record TenantQuota(
        int maxConcurrentSessions,
        long dailyTokenLimit,
        long cpuQuota,
        String memoryLimit,
        int pidsLimit
) {}
