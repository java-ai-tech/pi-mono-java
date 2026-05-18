package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.runtime.AgentRunContext;
import com.glmapper.coding.core.runtime.RunQueueDecision;
import com.glmapper.coding.core.runtime.RunQueueDecisionType;
import com.glmapper.coding.core.runtime.RunQueueManager;
import com.glmapper.coding.core.runtime.RunQueueMode;
import com.glmapper.coding.core.runtime.RuntimeEventSink;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class DistributedRunQueueManager implements RunQueueManager {
    private static final Logger log = LoggerFactory.getLogger(DistributedRunQueueManager.class);
    private static final long PROCESSING_TIMEOUT_MS = TimeUnit.MINUTES.toMillis(5);
    private static final long RECOVERY_INTERVAL_MS = TimeUnit.MINUTES.toMillis(1);
    private static final Duration RECOVERY_LOCK_TTL = Duration.ofMinutes(2);

    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final ClusterNodeIdentity nodeIdentity;
    private final ObjectMapper objectMapper;
    private final RuntimeEventSink globalSink;
    private final ScheduledExecutorService recoveryExecutor;
    private final DefaultRedisScript<String> pollScript;
    private final DefaultRedisScript<Long> ackScript;
    private final DefaultRedisScript<Long> requeueScript;

    public DistributedRunQueueManager(StringRedisTemplate redisTemplate,
                                      ClusterKeyRegistry keyRegistry,
                                      ClusterNodeIdentity nodeIdentity,
                                      ObjectMapper objectMapper,
                                      RuntimeEventSink globalSink) {
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.nodeIdentity = nodeIdentity;
        this.objectMapper = objectMapper;
        this.globalSink = globalSink;
        this.pollScript = buildPollScript();
        this.ackScript = buildAckScript();
        this.requeueScript = buildRequeueScript();
        this.recoveryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "queue-recovery");
            t.setDaemon(true);
            return t;
        });
        this.recoveryExecutor.scheduleAtFixedRate(
                this::runRecovery,
                RECOVERY_INTERVAL_MS,
                RECOVERY_INTERVAL_MS,
                TimeUnit.MILLISECONDS
        );
    }

    @PreDestroy
    public void shutdown() {
        recoveryExecutor.shutdownNow();
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
            String token = UUID.randomUUID().toString();
            String envelope = objectMapper.writeValueAsString(new QueueEnvelope(token, context));
            Long size = redisTemplate.opsForList().rightPush(key, envelope);
            return size == null ? 0 : size.intValue();
        } catch (Exception e) {
            log.error("Failed to enqueue run to Redis: namespace={}, sessionId={}, runId={}",
                    context.namespace(), context.sessionId(), context.runId(), e);
            throw new RuntimeException("Failed to enqueue run", e);
        }
    }

    @Override
    public Optional<PolledRun> pollNext(String namespace, String sessionId) {
        String queueKey = keyRegistry.runQueueKey(namespace, sessionId);
        String processingKey = keyRegistry.runQueueProcessingKey(namespace, sessionId);
        String envelope = redisTemplate.execute(
                pollScript,
                List.of(queueKey, processingKey),
                String.valueOf(System.currentTimeMillis())
        );
        if (envelope == null) {
            return Optional.empty();
        }
        try {
            QueueEnvelope queueEnvelope = objectMapper.readValue(envelope, QueueEnvelope.class);
            return Optional.of(new PolledRun(queueEnvelope.context, globalSink, envelope));
        } catch (Exception e) {
            log.error("Failed to deserialize queued run; removing from processing set: namespace={}, sessionId={}",
                    namespace, sessionId, e);
            // Remove the bad envelope from processing set to avoid recovery loops
            redisTemplate.opsForZSet().remove(processingKey, envelope);
            return Optional.empty();
        }
    }

    @Override
    public void ack(String namespace, String sessionId, PolledRun polledRun) {
        String processingKey = keyRegistry.runQueueProcessingKey(namespace, sessionId);
        try {
            redisTemplate.execute(
                    ackScript,
                    List.of(processingKey),
                    polledRun.token()
            );
        } catch (Exception e) {
            log.warn("Failed to ack polled run: namespace={}, sessionId={}", namespace, sessionId, e);
        }
    }

    @Override
    public void requeue(String namespace, String sessionId, PolledRun polledRun) {
        String queueKey = keyRegistry.runQueueKey(namespace, sessionId);
        String processingKey = keyRegistry.runQueueProcessingKey(namespace, sessionId);
        try {
            redisTemplate.execute(
                    requeueScript,
                    List.of(processingKey, queueKey),
                    polledRun.token()
            );
        } catch (Exception e) {
            log.warn("Failed to requeue polled run: namespace={}, sessionId={}", namespace, sessionId, e);
        }
    }

    @Override
    public int queueSize(String namespace, String sessionId) {
        String key = keyRegistry.runQueueKey(namespace, sessionId);
        Long size = redisTemplate.opsForList().size(key);
        return size == null ? 0 : size.intValue();
    }

    /**
     * Scan all processing sets and requeue items that have been there longer than the timeout.
     * Uses a distributed lock to ensure only one node runs recovery at a time.
     */
    void runRecovery() {
        String lockKey = keyRegistry.runQueueRecoveryLockKey();
        String lockValue = nodeIdentity.getNodeId() + ":" + UUID.randomUUID();
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(lockKey, lockValue, RECOVERY_LOCK_TTL);
        if (Boolean.FALSE.equals(acquired)) {
            return;
        }
        try {
            Set<String> processingKeys = redisTemplate.keys(keyRegistry.getPrefix() + ":queue:processing:*");
            if (processingKeys == null || processingKeys.isEmpty()) {
                return;
            }
            long threshold = System.currentTimeMillis() - PROCESSING_TIMEOUT_MS;
            for (String processingKey : processingKeys) {
                String queueKey = processingKey.replace(":queue:processing:", ":queue:session:");
                Set<ZSetOperations.TypedTuple<String>> stale =
                        redisTemplate.opsForZSet().rangeByScoreWithScores(processingKey, 0, threshold);
                if (stale == null) continue;
                for (ZSetOperations.TypedTuple<String> tuple : stale) {
                    String envelope = tuple.getValue();
                    if (envelope == null) continue;
                    Long removed = redisTemplate.opsForZSet().remove(processingKey, envelope);
                    if (removed != null && removed > 0) {
                        redisTemplate.opsForList().rightPush(queueKey, envelope);
                        log.info("Recovered stale queued run: processingKey={}, score={}",
                                processingKey, tuple.getScore());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Queue recovery scan failed", e);
        } finally {
            // release lock only if still owner
            String current = redisTemplate.opsForValue().get(lockKey);
            if (lockValue.equals(current)) {
                redisTemplate.delete(lockKey);
            }
        }
    }

    private DefaultRedisScript<String> buildPollScript() {
        String lua = """
                local queueKey = KEYS[1]
                local processingKey = KEYS[2]
                local nowMs = tonumber(ARGV[1])
                local envelope = redis.call('LPOP', queueKey)
                if envelope then
                    redis.call('ZADD', processingKey, nowMs, envelope)
                    return envelope
                end
                return nil
                """;
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(String.class);
        return script;
    }

    private DefaultRedisScript<Long> buildAckScript() {
        String lua = """
                local processingKey = KEYS[1]
                local token = ARGV[1]
                local items = redis.call('ZRANGE', processingKey, 0, -1)
                for _, envelope in ipairs(items) do
                    if string.find(envelope, token, 1, true) then
                        redis.call('ZREM', processingKey, envelope)
                        return 1
                    end
                end
                return 0
                """;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(Long.class);
        return script;
    }

    private DefaultRedisScript<Long> buildRequeueScript() {
        String lua = """
                local processingKey = KEYS[1]
                local queueKey = KEYS[2]
                local token = ARGV[1]
                local items = redis.call('ZRANGE', processingKey, 0, -1)
                for _, envelope in ipairs(items) do
                    if string.find(envelope, token, 1, true) then
                        redis.call('ZREM', processingKey, envelope)
                        redis.call('LPUSH', queueKey, envelope)
                        return 1
                    end
                end
                return 0
                """;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(Long.class);
        return script;
    }

    /**
     * Envelope wrapping a queued context with an opaque ack token.
     */
    private record QueueEnvelope(String token, AgentRunContext context) {
        QueueEnvelope() {
            this(null, null);
        }
    }
}
