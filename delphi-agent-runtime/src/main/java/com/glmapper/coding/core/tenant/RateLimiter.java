package com.glmapper.coding.core.tenant;

import com.glmapper.coding.core.config.PiAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Sliding-window rate limiter per namespace.
 * Tracks request timestamps within the last 60 seconds and enforces an RPM (requests per minute) limit.
 */
@Component
public class RateLimiter {
    private static final Logger log = LoggerFactory.getLogger(RateLimiter.class);
    private static final long WINDOW_MS = 60_000L;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Long>> windows = new ConcurrentHashMap<>();
    private final PiAgentProperties properties;

    public RateLimiter(PiAgentProperties properties) {
        this.properties = properties;
    }

    /**
     * Attempts to acquire a rate-limit permit for the given namespace.
     *
     * @return true if the request is allowed, false if rate-limited
     */
    public boolean tryAcquire(String namespace) {
        if (properties.rateLimit() == null || !properties.rateLimit().enabled()) {
            return true;
        }

        int rpm = properties.rateLimit().defaultRpm();
        if (rpm <= 0) {
            return true;
        }

        long now = System.currentTimeMillis();
        long cutoff = now - WINDOW_MS;

        ConcurrentLinkedDeque<Long> deque = windows.computeIfAbsent(namespace, k -> new ConcurrentLinkedDeque<>());

        // Remove expired timestamps from the front
        while (!deque.isEmpty()) {
            Long first = deque.peekFirst();
            if (first != null && first < cutoff) {
                deque.pollFirst();
            } else {
                break;
            }
        }

        if (deque.size() >= rpm) {
            log.debug("Rate limit exceeded for namespace '{}': {}/{} rpm", namespace, deque.size(), rpm);
            return false;
        }

        deque.addLast(now);
        return true;
    }

    /**
     * Periodically clean up empty deques to prevent memory leaks from inactive namespaces.
     */
    @Scheduled(fixedDelay = 120_000)
    public void cleanup() {
        Iterator<Map.Entry<String, ConcurrentLinkedDeque<Long>>> it = windows.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ConcurrentLinkedDeque<Long>> entry = it.next();
            if (entry.getValue().isEmpty()) {
                it.remove();
            }
        }
    }
}
