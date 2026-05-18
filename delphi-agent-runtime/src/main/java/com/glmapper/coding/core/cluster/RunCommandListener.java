package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.runtime.LiveRunRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class RunCommandListener implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(RunCommandListener.class);

    private final ObjectMapper objectMapper;
    private final Map<String, LiveRunRegistry.ActiveRun> localHandles;

    public RunCommandListener(ObjectMapper objectMapper,
                              Map<String, LiveRunRegistry.ActiveRun> localHandles) {
        this.objectMapper = objectMapper;
        this.localHandles = localHandles;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RunCommandMessage cmd = objectMapper.readValue(message.getBody(), RunCommandMessage.class);
            handleCommand(cmd);
        } catch (Exception e) {
            log.warn("Failed to process run command message", e);
        }
    }

    private void handleCommand(RunCommandMessage cmd) {
        LiveRunRegistry.ActiveRun run = findLocalRun(cmd.namespace(), cmd.sessionId());
        if (run == null) {
            log.debug("No local run found for command: type={}, session={}", cmd.type(), cmd.sessionId());
            return;
        }
        switch (cmd.type()) {
            case RunCommandMessage.TYPE_ABORT -> run.abort(cmd.payload());
            case RunCommandMessage.TYPE_STEER -> run.steer(cmd.payload());
            default -> log.warn("Unknown command type: {}", cmd.type());
        }
    }

    private LiveRunRegistry.ActiveRun findLocalRun(String namespace, String sessionId) {
        return localHandles.values().stream()
                .filter(r -> namespace.equals(r.namespace()) && sessionId.equals(r.sessionId()))
                .findFirst()
                .orElse(null);
    }
}
