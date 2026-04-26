package com.glmapper.coding.core.runtime.subagent;

import com.glmapper.coding.core.tenant.TenantQuota;
import com.glmapper.coding.core.tenant.TenantQuotaManager;
import org.springframework.stereotype.Component;

@Component
public class SubagentQuotaGuard {

    private static final int DEFAULT_MAX_SUBAGENTS_PER_RUN = 8;

    private final TenantQuotaManager tenantQuotaManager;
    private final SubagentRegistry subagentRegistry;

    public SubagentQuotaGuard(TenantQuotaManager tenantQuotaManager,
                              SubagentRegistry subagentRegistry) {
        this.tenantQuotaManager = tenantQuotaManager;
        this.subagentRegistry = subagentRegistry;
    }

    public void ensureAllowed(SubagentContext context) {
        TenantQuota tenantQuota = tenantQuotaManager.resolve(context.namespace());
        int tenantLimit = Math.max(1, tenantQuota.maxConcurrentSessions() * 2);
        if (subagentRegistry.activeCountByTenant(context.tenantId()) >= tenantLimit) {
            throw new IllegalStateException("tenant subagent quota exceeded");
        }
        if (subagentRegistry.activeCountByParentRun(context.parentRunId()) >= DEFAULT_MAX_SUBAGENTS_PER_RUN) {
            throw new IllegalStateException("parent run subagent quota exceeded");
        }
    }
}

