package com.glmapper.coding.http.api.config;

import com.glmapper.coding.core.cluster.ClusterNodeIdentity;
import com.glmapper.coding.core.config.PiAgentProperties;
import com.glmapper.coding.core.runtime.RuntimeEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SessionEventBrokerTest {

    private SessionEventBroker broker;
    private ClusterNodeIdentity nodeIdentity;

    @BeforeEach
    void setUp() {
        PiAgentProperties props = new PiAgentProperties(
                null, null, null, null, null, null, null, null, null, null,
                new PiAgentProperties.ClusterConfig(false, "test-node", null, null, null, 0)
        );
        nodeIdentity = new ClusterNodeIdentity(props);
        ObjectProvider<RedisSseEventPublisher> noPublisher = new ObjectProvider<>() {
            @Override public RedisSseEventPublisher getIfAvailable() { return null; }
            @Override public RedisSseEventPublisher getObject() { throw new UnsupportedOperationException(); }
        };
        broker = new SessionEventBroker(nodeIdentity, noPublisher);
    }

    @Test
    void registerAndPublishRoutesEventToEmitter() {
        SseEmitter emitter = broker.register("ns", "sess1", "run1", 0L);
        assertNotNull(emitter);
        assertEquals(1, broker.activeEmitterCount("ns", "sess1"));

        RuntimeEvent event = event("message_delta", "ns", "sess1", "run1");
        assertDoesNotThrow(() -> broker.publish(event));
    }

    @Test
    void publishDoesNotRouteToMismatchedRunId() {
        broker.register("ns", "sess1", "run1", 0L);
        broker.register("ns", "sess1", "run2", 0L);
        assertEquals(2, broker.activeEmitterCount("ns", "sess1"));
    }

    @Test
    void commandEmitterReceivesAllSessionEvents() {
        SseEmitter emitter = broker.registerCommand("ns", "sess1", 0L);
        assertNotNull(emitter);
        assertEquals(1, broker.activeEmitterCount("ns", "sess1"));
    }

    @Test
    void sendLocalIgnoresNullEvent() {
        assertDoesNotThrow(() -> broker.sendLocal(null));
    }

    @Test
    void sendLocalIgnoresEventWithNullSession() {
        RuntimeEvent event = new RuntimeEvent("e1", "test", "r1", "t1", "ns", "u1", null, null, Instant.now(), Map.of());
        assertDoesNotThrow(() -> broker.sendLocal(event));
    }

    private RuntimeEvent event(String name, String namespace, String sessionId, String runId) {
        return new RuntimeEvent(
                "evt_test", name, runId, "tenant1", namespace, "user1",
                sessionId, null, Instant.now(), Map.of("delta", "hello")
        );
    }
}
