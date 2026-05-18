package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class SubagentCommandDispatcher {
    private static final Logger log = LoggerFactory.getLogger(SubagentCommandDispatcher.class);

    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final ClusterNodeIdentity nodeIdentity;
    private final ObjectMapper objectMapper;

    public SubagentCommandDispatcher(StringRedisTemplate redisTemplate,
                                     ClusterKeyRegistry keyRegistry,
                                     ClusterNodeIdentity nodeIdentity,
                                     ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.nodeIdentity = nodeIdentity;
        this.objectMapper = objectMapper;
    }

    public boolean sendAbort(String ownerNodeId, String subagentId, String reason) {
        if (ownerNodeId == null || ownerNodeId.isBlank()) {
            return false;
        }
        SubagentCommandMessage msg = new SubagentCommandMessage(
                UUID.randomUUID().toString(),
                SubagentCommandMessage.TYPE_ABORT,
                subagentId,
                reason,
                nodeIdentity.getNodeId()
        );
        try {
            String channel = keyRegistry.subagentCommandsKey(ownerNodeId);
            String json = objectMapper.writeValueAsString(msg);
            redisTemplate.convertAndSend(channel, json);
            return true;
        } catch (Exception e) {
            log.warn("Failed to dispatch subagent abort command: subagentId={}, ownerNodeId={}",
                    subagentId, ownerNodeId, e);
            return false;
        }
    }
}
