package com.glmapper.coding.core.tenant;

import com.glmapper.coding.core.config.PiAgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TenantQuotaManagerTest {

    @Test
    void resolveReturnsDefaultsWhenNoOverride() {
        PiAgentProperties.QuotaDefaults defaults = new PiAgentProperties.QuotaDefaults(
                50, 1_000_000L, 50000L, "256m", 100);
        PiAgentProperties props = new PiAgentProperties(
                List.of(), null, null, null, null, List.of(),
                null,
                new PiAgentProperties.QuotaConfig(true, defaults, Map.of()),
                null, null
        );

        TenantQuotaManager manager = new TenantQuotaManager(props);
        TenantQuota quota = manager.resolve("some-tenant");

        assertEquals(50, quota.maxConcurrentSessions());
        assertEquals(1_000_000L, quota.dailyTokenLimit());
        assertEquals(50000L, quota.cpuQuota());
        assertEquals("256m", quota.memoryLimit());
        assertEquals(100, quota.pidsLimit());
    }

    @Test
    void resolveReturnsOverrideWhenPresent() {
        PiAgentProperties.QuotaDefaults defaults = new PiAgentProperties.QuotaDefaults(
                50, 1_000_000L, 50000L, "256m", 100);
        PiAgentProperties.QuotaDefaults premium = new PiAgentProperties.QuotaDefaults(
                200, 10_000_000L, 100000L, "512m", 200);

        PiAgentProperties props = new PiAgentProperties(
                List.of(), null, null, null, null, List.of(),
                null,
                new PiAgentProperties.QuotaConfig(true, defaults, Map.of("premium-tenant", premium)),
                null, null
        );

        TenantQuotaManager manager = new TenantQuotaManager(props);
        TenantQuota quota = manager.resolve("premium-tenant");

        assertEquals(200, quota.maxConcurrentSessions());
        assertEquals(10_000_000L, quota.dailyTokenLimit());
        assertEquals(100000L, quota.cpuQuota());
        assertEquals("512m", quota.memoryLimit());
        assertEquals(200, quota.pidsLimit());
    }

    @Test
    void resolveReturnsPermissiveDefaultsWhenDisabled() {
        PiAgentProperties props = new PiAgentProperties(
                List.of(), null, null, null, null, List.of(),
                null,
                new PiAgentProperties.QuotaConfig(false, null, null),
                null, null
        );

        TenantQuotaManager manager = new TenantQuotaManager(props);
        TenantQuota quota = manager.resolve("any-ns");

        assertEquals(Integer.MAX_VALUE, quota.maxConcurrentSessions());
        assertEquals(Long.MAX_VALUE, quota.dailyTokenLimit());
    }

    @Test
    void resolveHandlesNullQuotaConfig() {
        PiAgentProperties props = new PiAgentProperties(
                List.of(), null, null, null, null, List.of(),
                null, null, null, null
        );

        TenantQuotaManager manager = new TenantQuotaManager(props);
        TenantQuota quota = manager.resolve("any-ns");

        assertEquals(Integer.MAX_VALUE, quota.maxConcurrentSessions());
    }
}
