package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.glmapper.coding.core.config.PiAgentProperties;
import com.glmapper.coding.core.runtime.AgentRunContext;
import com.glmapper.coding.core.runtime.RunQueueDecision;
import com.glmapper.coding.core.runtime.RunQueueDecisionType;
import com.glmapper.coding.core.runtime.RunQueueManager;
import com.glmapper.coding.core.runtime.RunQueueMode;
import com.glmapper.coding.core.runtime.RuntimeEvent;
import com.glmapper.coding.core.runtime.RuntimeEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedRunQueueManagerTest {

    private StringRedisTemplate redisTemplate;
    private ListOperations<String, String> listOps;
    private ClusterKeyRegistry keyRegistry;
    private ObjectMapper objectMapper;
    private RuntimeEventSink globalSink;
    private DistributedRunQueueManager manager;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        listOps = mock(ListOperations.class);
        when(redisTemplate.opsForList()).thenReturn(listOps);

        PiAgentProperties props = new PiAgentProperties(
                null, null, null, null, null, null, null, null, null, null,
                new PiAgentProperties.ClusterConfig(true, "test-node",
                        new PiAgentProperties.ClusterConfig.RedisConfig("delphi"),
                        null, null, 0)
        );
        keyRegistry = new ClusterKeyRegistry(props);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        globalSink = event -> { };

        manager = new DistributedRunQueueManager(redisTemplate, keyRegistry, objectMapper, globalSink);
    }

    @Test
    void decideReturnsRunNowWhenNoActiveRun() {
        AgentRunContext ctx = newContext("run1", RunQueueMode.FOLLOWUP);
        RunQueueDecision decision = manager.decide(ctx, false);
        assertEquals(RunQueueDecisionType.RUN_NOW, decision.type());
    }

    @Test
    void decideReturnsEnqueueForFollowupWithActiveRun() {
        AgentRunContext ctx = newContext("run1", RunQueueMode.FOLLOWUP);
        RunQueueDecision decision = manager.decide(ctx, true);
        assertEquals(RunQueueDecisionType.ENQUEUE, decision.type());
    }

    @Test
    void enqueueRpushesSerializedContext() {
        AgentRunContext ctx = newContext("run1", RunQueueMode.FOLLOWUP);
        when(listOps.rightPush(eq("delphi:queue:session:ns:sess1"), any(String.class)))
                .thenReturn(1L);

        int size = manager.enqueue(ctx, globalSink);

        assertEquals(1, size);
        verify(listOps).rightPush(eq("delphi:queue:session:ns:sess1"), any(String.class));
    }

    @Test
    void pollNextReturnsEmptyWhenQueueEmpty() {
        when(listOps.leftPop("delphi:queue:session:ns:sess1")).thenReturn(null);
        Optional<RunQueueManager.QueuedRun> result = manager.pollNext("ns", "sess1");
        assertTrue(result.isEmpty());
    }

    @Test
    void pollNextDeserializesContextAndAttachesGlobalSink() throws Exception {
        AgentRunContext ctx = newContext("run1", RunQueueMode.FOLLOWUP);
        String json = objectMapper.writeValueAsString(ctx);
        when(listOps.leftPop("delphi:queue:session:ns:sess1")).thenReturn(json);

        Optional<RunQueueManager.QueuedRun> result = manager.pollNext("ns", "sess1");

        assertTrue(result.isPresent());
        assertEquals("run1", result.get().context().runId());
        assertSame(globalSink, result.get().sink());
    }

    @Test
    void queueSizeReturnsRedisListLength() {
        when(listOps.size("delphi:queue:session:ns:sess1")).thenReturn(3L);
        assertEquals(3, manager.queueSize("ns", "sess1"));
    }

    @Test
    void queueSizeReturnsZeroWhenKeyMissing() {
        when(listOps.size("delphi:queue:session:ns:sess1")).thenReturn(null);
        assertEquals(0, manager.queueSize("ns", "sess1"));
    }

    private AgentRunContext newContext(String runId, RunQueueMode mode) {
        return new AgentRunContext(
                runId, "tenant1", "ns", "user1", "proj",
                "sess1", "hello", "openai", "gpt-4", null,
                mode, Instant.parse("2025-01-01T00:00:00Z"), Map.of()
        );
    }
}
