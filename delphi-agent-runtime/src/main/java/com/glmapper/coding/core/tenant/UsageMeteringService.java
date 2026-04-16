package com.glmapper.coding.core.tenant;

import com.glmapper.ai.api.Usage;
import com.glmapper.coding.core.mongo.UsageMetricsDocument;
import com.glmapper.coding.core.config.PiAgentProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Accumulates usage metrics in memory and periodically flushes to MongoDB.
 * Array indices: 0=inputTokens, 1=outputTokens, 2=requests, 3=toolCalls
 */
@Component
public class UsageMeteringService {
    private static final Logger log = LoggerFactory.getLogger(UsageMeteringService.class);

    private final ConcurrentHashMap<String, AtomicLongArray> cache = new ConcurrentHashMap<>();
    private final MongoTemplate mongoTemplate;
    private final PiAgentProperties properties;

    public UsageMeteringService(MongoTemplate mongoTemplate, PiAgentProperties properties) {
        this.mongoTemplate = mongoTemplate;
        this.properties = properties;
    }

    public void recordPromptUsage(String namespace, Usage usage) {
        if (!isEnabled() || usage == null) return;
        AtomicLongArray counters = cache.computeIfAbsent(namespace, k -> new AtomicLongArray(4));
        counters.addAndGet(0, usage.input());
        counters.addAndGet(1, usage.output());
        counters.incrementAndGet(2);
    }

    public void recordToolCall(String namespace) {
        if (!isEnabled()) return;
        AtomicLongArray counters = cache.computeIfAbsent(namespace, k -> new AtomicLongArray(4));
        counters.incrementAndGet(3);
    }

    /**
     * Returns today's usage for a namespace from the in-memory cache.
     * Falls back to MongoDB if not in cache.
     */
    public UsageMetricsDocument getTodayUsage(String namespace) {
        UsageMetricsDocument doc = new UsageMetricsDocument();
        doc.setNamespace(namespace);
        doc.setDate(LocalDate.now());

        AtomicLongArray counters = cache.get(namespace);
        if (counters != null) {
            doc.setTotalInputTokens(counters.get(0));
            doc.setTotalOutputTokens(counters.get(1));
            doc.setTotalRequests(counters.get(2));
            doc.setTotalToolCalls(counters.get(3));
        }
        return doc;
    }

    @Scheduled(fixedDelayString = "${pi.metering.flush-interval-seconds:30}000")
    public void flush() {
        if (!isEnabled()) return;
        LocalDate today = LocalDate.now();

        for (Map.Entry<String, AtomicLongArray> entry : cache.entrySet()) {
            String namespace = entry.getKey();
            AtomicLongArray counters = entry.getValue();

            long input = counters.getAndSet(0, 0);
            long output = counters.getAndSet(1, 0);
            long requests = counters.getAndSet(2, 0);
            long toolCalls = counters.getAndSet(3, 0);

            if (input == 0 && output == 0 && requests == 0 && toolCalls == 0) {
                continue;
            }

            try {
                Query query = Query.query(
                        Criteria.where("namespace").is(namespace).and("date").is(today));
                Update update = new Update()
                        .inc("totalInputTokens", input)
                        .inc("totalOutputTokens", output)
                        .inc("totalRequests", requests)
                        .inc("totalToolCalls", toolCalls);
                mongoTemplate.upsert(query, update, UsageMetricsDocument.class);
            } catch (Exception e) {
                // Put values back on failure so they aren't lost
                counters.addAndGet(0, input);
                counters.addAndGet(1, output);
                counters.addAndGet(2, requests);
                counters.addAndGet(3, toolCalls);
                log.warn("Failed to flush usage metrics for namespace '{}'", namespace, e);
            }
        }
    }

    @PreDestroy
    public void onShutdown() {
        flush();
    }

    private boolean isEnabled() {
        return properties.metering() != null && properties.metering().enabled();
    }
}
