package com.glmapper.coding.http.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.cluster.ClusterNodeIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

public class RedisSseEventSubscriber implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(RedisSseEventSubscriber.class);

    private final SessionEventBroker broker;
    private final ClusterNodeIdentity nodeIdentity;
    private final ObjectMapper objectMapper;

    public RedisSseEventSubscriber(SessionEventBroker broker,
                                   ClusterNodeIdentity nodeIdentity,
                                   ObjectMapper objectMapper) {
        this.broker = broker;
        this.nodeIdentity = nodeIdentity;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            SseEventEnvelope envelope = objectMapper.readValue(message.getBody(), SseEventEnvelope.class);
            if (nodeIdentity.getNodeId().equals(envelope.sourceNodeId())) {
                return;
            }
            broker.sendLocal(envelope.event());
        } catch (Exception e) {
            log.warn("Failed to process Redis SSE event message", e);
        }
    }
}
