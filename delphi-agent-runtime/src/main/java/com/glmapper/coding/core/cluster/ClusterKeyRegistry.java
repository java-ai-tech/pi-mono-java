package com.glmapper.coding.core.cluster;

import com.glmapper.coding.core.config.PiAgentProperties;
import org.springframework.stereotype.Component;

@Component
public class ClusterKeyRegistry {
    private static final String DEFAULT_PREFIX = "delphi";

    private final String prefix;

    public ClusterKeyRegistry(PiAgentProperties properties) {
        this.prefix = resolvePrefix(properties);
    }

    public String getPrefix() {
        return prefix;
    }

    public String activeRunKey(String runId) {
        return prefix + ":run:active:" + runId;
    }

    public String runBySessionKey(String namespace, String sessionId) {
        return prefix + ":run:by-session:" + namespace + ":" + sessionId;
    }

    public String sessionLockKey(String namespace, String sessionId) {
        return prefix + ":session:lock:" + namespace + ":" + sessionId;
    }

    public String runQueueKey(String namespace, String sessionId) {
        return prefix + ":queue:session:" + namespace + ":" + sessionId;
    }

    public String runQueueProcessingKey(String namespace, String sessionId) {
        return prefix + ":queue:processing:" + namespace + ":" + sessionId;
    }

    public String runQueueRecoveryLockKey() {
        return prefix + ":queue:recovery:lock";
    }

    public String runCommandsKey(String nodeId) {
        return prefix + ":run:commands:" + nodeId;
    }

    public String subagentCommandsKey(String nodeId) {
        return prefix + ":subagent:commands:" + nodeId;
    }

    public String sseEventsChannel() {
        return prefix + ":sse:events";
    }

    public String rateLimitKey(String namespace) {
        return prefix + ":ratelimit:" + namespace;
    }

    public String usageKey(String yyyyMMdd, String namespace, String metric) {
        return prefix + ":usage:" + yyyyMMdd + ":" + namespace + ":" + metric;
    }

    public String cacheInvalidateChannel() {
        return prefix + ":cache:invalidate";
    }

    public String nodeRegistryKey(String nodeId) {
        return prefix + ":node:registry:" + nodeId;
    }

    public String tenantActiveCountKey(String namespace) {
        return prefix + ":run:tenant-active:" + namespace;
    }

    public String userActiveCountKey(String namespace, String userId) {
        return prefix + ":run:user-active:" + namespace + ":" + userId;
    }

    private String resolvePrefix(PiAgentProperties properties) {
        if (properties.cluster() == null || properties.cluster().redis() == null) {
            return DEFAULT_PREFIX;
        }
        String configured = properties.cluster().redis().keyPrefix();
        return (configured == null || configured.isBlank()) ? DEFAULT_PREFIX : configured;
    }
}
