package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class RunCommandDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RunCommandDispatcher.class);

    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final ClusterNodeIdentity nodeIdentity;
    private final ObjectMapper objectMapper;

    public RunCommandDispatcher(StringRedisTemplate redisTemplate,
                                ClusterKeyRegistry keyRegistry,
                                ClusterNodeIdentity nodeIdentity,
                                ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.nodeIdentity = nodeIdentity;
        this.objectMapper = objectMapper;
    }

    public boolean sendAbort(String namespace, String sessionId, String reason) {
        String ownerNodeId = resolveOwnerNode(namespace, sessionId);
        if (ownerNodeId == null) {
            return false;
        }
        RunCommandMessage msg = new RunCommandMessage(
                UUID.randomUUID().toString(),
                RunCommandMessage.TYPE_ABORT,
                namespace, sessionId, null,
                reason, nodeIdentity.getNodeId());
        return publish(ownerNodeId, msg);
    }

    public boolean sendSteer(String namespace, String sessionId, String text) {
        String ownerNodeId = resolveOwnerNode(namespace, sessionId);
        if (ownerNodeId == null) {
            return false;
        }
        RunCommandMessage msg = new RunCommandMessage(
                UUID.randomUUID().toString(),
                RunCommandMessage.TYPE_STEER,
                namespace, sessionId, null,
                text, nodeIdentity.getNodeId());
        return publish(ownerNodeId, msg);
    }

    private String resolveOwnerNode(String namespace, String sessionId) {
        String bySessionKey = keyRegistry.runBySessionKey(namespace, sessionId);
        String runId = redisTemplate.opsForValue().get(bySessionKey);
        if (runId == null) {
            return null;
        }
        String activeRunKey = keyRegistry.activeRunKey(runId);
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(activeRunKey);
        if (hash.isEmpty()) {
            return null;
        }
        return (String) hash.get("nodeId");
    }

    private boolean publish(String targetNodeId, RunCommandMessage msg) {
        try {
            String channel = keyRegistry.runCommandsKey(targetNodeId);
            String json = objectMapper.writeValueAsString(msg);
            redisTemplate.convertAndSend(channel, json);
            return true;
        } catch (Exception e) {
            log.warn("Failed to dispatch run command: type={}, session={}", msg.type(), msg.sessionId(), e);
            return false;
        }
    }
}
