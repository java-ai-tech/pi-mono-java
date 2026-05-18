package com.glmapper.coding.core.cluster;

import com.glmapper.coding.core.config.PiAgentProperties;
import com.glmapper.coding.core.runtime.AgentRunContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class RunAdmissionController {
    private static final Logger log = LoggerFactory.getLogger(RunAdmissionController.class);

    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final ClusterNodeIdentity nodeIdentity;
    private final long runTtlMs;

    private final DefaultRedisScript<Long> acquireScript;
    private final DefaultRedisScript<Long> releaseScript;

    public RunAdmissionController(StringRedisTemplate redisTemplate,
                                  ClusterKeyRegistry keyRegistry,
                                  ClusterNodeIdentity nodeIdentity,
                                  PiAgentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.nodeIdentity = nodeIdentity;
        this.runTtlMs = properties.cluster().run().maxTtlMs();
        this.acquireScript = buildAcquireScript();
        this.releaseScript = buildReleaseScript();
    }

    public AdmissionResult acquire(AgentRunContext context, int maxTenantConcurrent, int maxUserConcurrent) {
        String activeRunKey = keyRegistry.activeRunKey(context.runId());
        String bySessionKey = keyRegistry.runBySessionKey(context.namespace(), context.sessionId());
        String tenantActiveKey = keyRegistry.tenantActiveCountKey(context.namespace());
        String userActiveKey = keyRegistry.userActiveCountKey(context.namespace(), context.userId());

        String nodeId = nodeIdentity.getNodeId();
        long ttlMs = runTtlMs;

        Long result = redisTemplate.execute(
                acquireScript,
                List.of(activeRunKey, bySessionKey, tenantActiveKey, userActiveKey),
                context.runId(),
                context.namespace(),
                context.sessionId(),
                context.tenantId(),
                context.userId() == null ? "" : context.userId(),
                nodeId,
                String.valueOf(maxTenantConcurrent),
                String.valueOf(maxUserConcurrent),
                String.valueOf(ttlMs),
                String.valueOf(System.currentTimeMillis())
        );

        if (result == null || result == 0L) {
            return AdmissionResult.OK;
        } else if (result == 1L) {
            return AdmissionResult.SESSION_BUSY;
        } else if (result == 2L) {
            return AdmissionResult.TENANT_QUOTA_EXCEEDED;
        } else {
            return AdmissionResult.USER_QUOTA_EXCEEDED;
        }
    }

    public void release(String runId, String namespace, String sessionId, String userId) {
        String activeRunKey = keyRegistry.activeRunKey(runId);
        String bySessionKey = keyRegistry.runBySessionKey(namespace, sessionId);
        String tenantActiveKey = keyRegistry.tenantActiveCountKey(namespace);
        String userActiveKey = keyRegistry.userActiveCountKey(namespace, userId == null ? "" : userId);

        redisTemplate.execute(
                releaseScript,
                List.of(activeRunKey, bySessionKey, tenantActiveKey, userActiveKey),
                runId
        );
    }

    public enum AdmissionResult {
        OK, SESSION_BUSY, TENANT_QUOTA_EXCEEDED, USER_QUOTA_EXCEEDED
    }

    private DefaultRedisScript<Long> buildAcquireScript() {
        String lua = """
                local activeRunKey = KEYS[1]
                local bySessionKey = KEYS[2]
                local tenantActiveKey = KEYS[3]
                local userActiveKey = KEYS[4]
                local runId = ARGV[1]
                local namespace = ARGV[2]
                local sessionId = ARGV[3]
                local tenantId = ARGV[4]
                local userId = ARGV[5]
                local nodeId = ARGV[6]
                local maxTenant = tonumber(ARGV[7])
                local maxUser = tonumber(ARGV[8])
                local ttlMs = tonumber(ARGV[9])
                local startedAt = ARGV[10]
                if redis.call('EXISTS', bySessionKey) == 1 then
                    return 1
                end
                local tenantCount = redis.call('SCARD', tenantActiveKey)
                if tenantCount >= maxTenant then
                    return 2
                end
                if userId ~= '' and maxUser > 0 then
                    local userCount = redis.call('SCARD', userActiveKey)
                    if userCount >= maxUser then
                        return 3
                    end
                end
                redis.call('HSET', activeRunKey,
                    'runId', runId,
                    'namespace', namespace,
                    'sessionId', sessionId,
                    'tenantId', tenantId,
                    'userId', userId,
                    'nodeId', nodeId,
                    'status', 'RUNNING',
                    'startedAt', startedAt)
                redis.call('PEXPIRE', activeRunKey, ttlMs)
                redis.call('SET', bySessionKey, runId, 'PX', ttlMs)
                redis.call('SADD', tenantActiveKey, runId)
                redis.call('PEXPIRE', tenantActiveKey, ttlMs)
                if userId ~= '' then
                    redis.call('SADD', userActiveKey, runId)
                    redis.call('PEXPIRE', userActiveKey, ttlMs)
                end
                return 0
                """;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(Long.class);
        return script;
    }

    private DefaultRedisScript<Long> buildReleaseScript() {
        String lua = """
                local activeRunKey = KEYS[1]
                local bySessionKey = KEYS[2]
                local tenantActiveKey = KEYS[3]
                local userActiveKey = KEYS[4]
                local runId = ARGV[1]
                redis.call('DEL', activeRunKey)
                redis.call('DEL', bySessionKey)
                redis.call('SREM', tenantActiveKey, runId)
                redis.call('SREM', userActiveKey, runId)
                return 0
                """;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(Long.class);
        return script;
    }
}
