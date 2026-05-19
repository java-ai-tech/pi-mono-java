package com.glmapper.coding.core.tenant;

import com.glmapper.ai.api.Usage;
import com.glmapper.coding.core.cluster.ClusterKeyRegistry;
import com.glmapper.coding.core.config.PiAgentProperties;
import com.glmapper.coding.core.mongo.UsageMetricsDocument;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

@Component
public class UsageMeteringService {
    private static final Logger log = LoggerFactory.getLogger(UsageMeteringService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String[] METRICS = {"inputTokens", "outputTokens", "requests", "toolCalls"};

    private final ConcurrentHashMap<String, AtomicLongArray> localCache = new ConcurrentHashMap<>();
    private final MongoOperations mongoTemplate;
    private final PiAgentProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final boolean clusterEnabled;
    private final DefaultRedisScript<Long> getAndDeleteScript;

    public UsageMeteringService(MongoOperations mongoTemplate,
                                PiAgentProperties properties,
                                ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                ClusterKeyRegistry keyRegistry) {
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
        this.keyRegistry = keyRegistry;
        this.clusterEnabled = properties.cluster() != null && properties.cluster().enabled();
        this.redisTemplate = clusterEnabled ? redisTemplateProvider.getIfAvailable() : null;
        this.getAndDeleteScript = buildGetAndDeleteScript();
    }

    public void recordPromptUsage(String namespace, Usage usage) {
        if (!isEnabled() || usage == null) return;
        if (clusterEnabled && redisTemplate != null) {
            String date = LocalDate.now().format(DATE_FMT);
            incrRedis(namespace, date, 0, usage.input());
            incrRedis(namespace, date, 1, usage.output());
            incrRedis(namespace, date, 2, 1);
        } else {
            AtomicLongArray counters = localCache.computeIfAbsent(namespace, k -> new AtomicLongArray(4));
            counters.addAndGet(0, usage.input());
            counters.addAndGet(1, usage.output());
            counters.incrementAndGet(2);
        }
    }

    public void recordToolCall(String namespace) {
        if (!isEnabled()) return;
        if (clusterEnabled && redisTemplate != null) {
            String date = LocalDate.now().format(DATE_FMT);
            incrRedis(namespace, date, 3, 1);
        } else {
            AtomicLongArray counters = localCache.computeIfAbsent(namespace, k -> new AtomicLongArray(4));
            counters.incrementAndGet(3);
        }
    }

    public UsageMetricsDocument getTodayUsage(String namespace) {
        LocalDate today = LocalDate.now();
        UsageMetricsDocument mongoDoc = findMongoUsage(namespace, today);

        long inputTokens = mongoDoc != null ? mongoDoc.getTotalInputTokens() : 0;
        long outputTokens = mongoDoc != null ? mongoDoc.getTotalOutputTokens() : 0;
        long requests = mongoDoc != null ? mongoDoc.getTotalRequests() : 0;
        long toolCalls = mongoDoc != null ? mongoDoc.getTotalToolCalls() : 0;

        if (clusterEnabled && redisTemplate != null) {
            String date = today.format(DATE_FMT);
            inputTokens += getRedisValue(namespace, date, 0);
            outputTokens += getRedisValue(namespace, date, 1);
            requests += getRedisValue(namespace, date, 2);
            toolCalls += getRedisValue(namespace, date, 3);
        } else {
            AtomicLongArray counters = localCache.get(namespace);
            if (counters != null) {
                inputTokens += counters.get(0);
                outputTokens += counters.get(1);
                requests += counters.get(2);
                toolCalls += counters.get(3);
            }
        }

        UsageMetricsDocument result = new UsageMetricsDocument();
        result.setNamespace(namespace);
        result.setDate(today);
        result.setTotalInputTokens(inputTokens);
        result.setTotalOutputTokens(outputTokens);
        result.setTotalRequests(requests);
        result.setTotalToolCalls(toolCalls);
        return result;
    }

    @Scheduled(fixedDelayString = "${pi.metering.flush-interval-seconds:30}000")
    public void flush() {
        if (!isEnabled()) return;
        if (clusterEnabled && redisTemplate != null) {
            flushFromRedis();
        } else {
            flushFromLocal();
        }
    }

    @PreDestroy
    public void onShutdown() {
        flush();
    }

    private void flushFromRedis() {
        String pattern = keyRegistry.getPrefix() + ":usage:*";
        Set<String> keys = redisTemplate.keys(pattern);
        if (keys == null || keys.isEmpty()) return;

        Map<UsageBucket, long[]> namespaceDeltas = new HashMap<>();
        for (String key : keys) {
            UsageKey usageKey = parseUsageKey(key);
            if (usageKey == null) continue;

            Long value = redisTemplate.execute(getAndDeleteScript, List.of(key));
            if (value == null || value == 0) continue;

            UsageBucket bucket = new UsageBucket(usageKey.date(), usageKey.namespace());
            namespaceDeltas.computeIfAbsent(bucket, k -> new long[4])[usageKey.metricIndex()] += value;
        }

        for (Map.Entry<UsageBucket, long[]> entry : namespaceDeltas.entrySet()) {
            UsageBucket bucket = entry.getKey();
            long[] deltas = entry.getValue();
            if (deltas[0] == 0 && deltas[1] == 0 && deltas[2] == 0 && deltas[3] == 0) continue;

            try {
                upsertUsage(bucket.namespace(), bucket.date(), deltas);
            } catch (Exception e) {
                String date = bucket.date().format(DATE_FMT);
                for (int i = 0; i < 4; i++) {
                    if (deltas[i] > 0) {
                        incrRedis(bucket.namespace(), date, i, deltas[i]);
                    }
                }
                log.warn("Failed to flush usage metrics for namespace '{}', date '{}', rolled back to Redis",
                        bucket.namespace(), bucket.date(), e);
            }
        }
    }

    private void flushFromLocal() {
        LocalDate today = LocalDate.now();
        for (Map.Entry<String, AtomicLongArray> entry : localCache.entrySet()) {
            String namespace = entry.getKey();
            AtomicLongArray counters = entry.getValue();

            long input = counters.getAndSet(0, 0);
            long output = counters.getAndSet(1, 0);
            long requests = counters.getAndSet(2, 0);
            long toolCalls = counters.getAndSet(3, 0);

            if (input == 0 && output == 0 && requests == 0 && toolCalls == 0) continue;

            try {
                upsertUsage(namespace, today, new long[]{input, output, requests, toolCalls});
            } catch (Exception e) {
                counters.addAndGet(0, input);
                counters.addAndGet(1, output);
                counters.addAndGet(2, requests);
                counters.addAndGet(3, toolCalls);
                log.warn("Failed to flush usage metrics for namespace '{}'", namespace, e);
            }
        }
    }

    private void incrRedis(String namespace, String date, int metricIdx, long delta) {
        String key = keyRegistry.usageKey(date, namespace, METRICS[metricIdx]);
        redisTemplate.opsForValue().increment(key, delta);
    }

    private long getRedisValue(String namespace, String date, int metricIdx) {
        String key = keyRegistry.usageKey(date, namespace, METRICS[metricIdx]);
        String val = redisTemplate.opsForValue().get(key);
        return val == null ? 0 : Long.parseLong(val);
    }

    private UsageMetricsDocument findMongoUsage(String namespace, LocalDate date) {
        Query query = Query.query(
                Criteria.where("namespace").is(namespace).and("date").is(date));
        return mongoTemplate.findOne(query, UsageMetricsDocument.class);
    }

    protected void upsertUsage(String namespace, LocalDate date, long[] deltas) {
        Query query = Query.query(
                Criteria.where("namespace").is(namespace).and("date").is(date));
        Update update = new Update()
                .inc("totalInputTokens", deltas[0])
                .inc("totalOutputTokens", deltas[1])
                .inc("totalRequests", deltas[2])
                .inc("totalToolCalls", deltas[3]);
        mongoTemplate.upsert(query, update, UsageMetricsDocument.class);
    }

    private int metricIndex(String metric) {
        for (int i = 0; i < METRICS.length; i++) {
            if (METRICS[i].equals(metric)) return i;
        }
        return -1;
    }

    private UsageKey parseUsageKey(String key) {
        String usagePrefix = keyRegistry.getPrefix() + ":usage:";
        if (!key.startsWith(usagePrefix)) {
            return null;
        }

        String payload = key.substring(usagePrefix.length());
        int dateEnd = payload.indexOf(':');
        int metricStart = payload.lastIndexOf(':');
        if (dateEnd <= 0 || metricStart <= dateEnd + 1 || metricStart == payload.length() - 1) {
            return null;
        }

        String datePart = payload.substring(0, dateEnd);
        String namespace = payload.substring(dateEnd + 1, metricStart);
        String metric = payload.substring(metricStart + 1);
        int metricIndex = metricIndex(metric);
        if (metricIndex < 0) {
            return null;
        }

        try {
            return new UsageKey(LocalDate.parse(datePart, DATE_FMT), namespace, metricIndex);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private boolean isEnabled() {
        return properties.metering() != null && properties.metering().enabled();
    }

    private record UsageBucket(LocalDate date, String namespace) {
    }

    private record UsageKey(LocalDate date, String namespace, int metricIndex) {
    }

    private DefaultRedisScript<Long> buildGetAndDeleteScript() {
        String lua = """
                local val = redis.call('GET', KEYS[1])
                if val then
                    redis.call('DEL', KEYS[1])
                    return tonumber(val)
                end
                return 0
                """;
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptText(lua);
        script.setResultType(Long.class);
        return script;
    }
}
