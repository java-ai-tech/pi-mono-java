package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.runtime.subagent.SubagentRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

public class SubagentCommandListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(SubagentCommandListener.class);

    private final ObjectMapper objectMapper;
    private final SubagentRegistry registry;

    public SubagentCommandListener(ObjectMapper objectMapper, SubagentRegistry registry) {
        this.objectMapper = objectMapper;
        this.registry = registry;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            SubagentCommandMessage cmd = objectMapper.readValue(message.getBody(), SubagentCommandMessage.class);
            if (SubagentCommandMessage.TYPE_ABORT.equals(cmd.type())) {
                boolean aborted = registry.abort(cmd.subagentId(), cmd.reason());
                if (!aborted) {
                    log.debug("Subagent not found locally for abort command: {}", cmd.subagentId());
                }
            } else {
                log.warn("Unknown subagent command type: {}", cmd.type());
            }
        } catch (Exception e) {
            log.warn("Failed to process subagent command message", e);
        }
    }
}
