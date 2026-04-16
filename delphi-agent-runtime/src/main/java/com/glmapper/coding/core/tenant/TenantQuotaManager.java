package com.glmapper.coding.core.tenant;

import com.glmapper.coding.core.config.PiAgentProperties;
import org.springframework.stereotype.Component;

/**
 * Resolves per-namespace resource quotas.
 * Uses namespace-specific overrides when available, otherwise falls back to defaults.
 */
@Component
public class TenantQuotaManager {

    private final PiAgentProperties properties;

    public TenantQuotaManager(PiAgentProperties properties) {
        this.properties = properties;
    }

    /**
     * Resolves the effective quota for a given namespace.
     * If quota is disabled or no config exists, returns sensible defaults.
     */
    public TenantQuota resolve(String namespace) {
        PiAgentProperties.QuotaConfig quotaConfig = properties.quota();
        if (quotaConfig == null || !quotaConfig.enabled()) {
            // Return permissive defaults when quota is disabled
            return new TenantQuota(Integer.MAX_VALUE, Long.MAX_VALUE, 50000, "256m", 100);
        }

        // Check for namespace-specific override
        if (quotaConfig.overrides() != null && namespace != null) {
            PiAgentProperties.QuotaDefaults override = quotaConfig.overrides().get(namespace);
            if (override != null) {
                return new TenantQuota(
                        override.maxConcurrentSessions(),
                        override.dailyTokenLimit(),
                        override.cpuQuota(),
                        override.memoryLimit(),
                        override.pidsLimit()
                );
            }
        }

        // Fall back to defaults
        PiAgentProperties.QuotaDefaults defaults = quotaConfig.defaults();
        if (defaults == null) {
            return new TenantQuota(50, 1_000_000, 50000, "256m", 100);
        }
        return new TenantQuota(
                defaults.maxConcurrentSessions(),
                defaults.dailyTokenLimit(),
                defaults.cpuQuota(),
                defaults.memoryLimit(),
                defaults.pidsLimit()
        );
    }
}
