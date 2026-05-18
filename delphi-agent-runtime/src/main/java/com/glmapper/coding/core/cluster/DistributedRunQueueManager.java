package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.runtime.AgentRunContext;
import com.glmapper.coding.core.runtime.RunQueueDecision;
import com.glmapper.coding.core.runtime.RunQueueDecisionType;
import com.glmapper.coding.core.runtime.RunQueueManager;
import com.glmapper.coding.core.runtime.RunQueueMode;
import com.glmapper.coding.core.runtime.RuntimeEventSink;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class DistributedRunQueueManager implements RunQueueManager {
    private static final Logger log = LoggerFactory.getLogger(DistributedRunQueueManager.class);

    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final ObjectMapper objectMapper;
    private final RuntimeEventSink globalSink;

    public DistributedRunQueueManager(StringRedisTemplate redisTemplate,
                                      ClusterKeyRegistry keyRegistry,
                                      ObjectMapper objectMapper,
                                      RuntimeEventSink globalSink) {
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.objectMapper = objectMapper;
        this.globalSink = globalSink;
    }

    @Override
    public RunQueueDecision decide(AgentRunContext context, boolean hasActiveRun) {
        if (!hasActiveRun) {
            return RunQueueDecision.runNow();
        }
        RunQueueMode mode = context.queueMode() == null ? RunQueueMode.INTERRUPT : context.queueMode();
        return switch (mode) {
            case FOLLOWUP -> new RunQueueDecision(RunQueueDecisionType.ENQUEUE, "active_run_followup");
            case STEER -> new RunQueueDecision(RunQueueDecisionType.STEER, "active_run_steer");
            case DROP -> new RunQueueDecision(RunQueueDecisionType.DROP, "active_run_drop");
            case REJECT -> new RunQueueDecision(RunQueueDecisionType.REJECT, "active_run_reject");
            case INTERRUPT -> new RunQueueDecision(RunQueueDecisionType.INTERRUPT, "active_run_interrupt");
        };
    }

    @Override
    public int enqueue(AgentRunContext context, RuntimeEventSink sink) {
        String key = keyRegistry.runQueueKey(context.namespace(), context.sessionId());
        try {
            String json = objectMapper.writeValueAsString(context);
            Long size = redisTemplate.opsForList().rightPush(key, json);
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            log.error("Failed to enqueue run to Redis: namespace={}, sessionId={}, runId={}",
                    context.namespace(), context.sessionId(), context.runId(), e);
            throw new RuntimeException("Failed to enqueue run", e);
        }
    }

    @Override
    public Optional<QueuedRun> pollNext(String namespace, String sessionId) {
        String key = keyRegistry.runQueueKey(namespace, sessionId);
        String json = redisTemplate.opsForList().leftPop(key);
        if (json == null) {
            return Optional.empty();
        }
        try {
            AgentRunContext context = objectMapper.readValue(json, AgentRunContext.class);
            return Optional.of(new QueuedRun(context, globalSink));
        } catch (Exception e) {
            log.error("Failed to deserialize queued run: namespace={}, sessionId={}, json={}",
                    namespace, sessionId, json, e);
            return Optional.empty();
        }
    }

    @Override
    public int queueSize(String namespace, String sessionId) {
        String key = keyRegistry.runQueueKey(namespace, sessionId);
        Long size = redisTemplate.opsForList().size(key);
        return size == null ? 0 : size.intValue();
    }
}
