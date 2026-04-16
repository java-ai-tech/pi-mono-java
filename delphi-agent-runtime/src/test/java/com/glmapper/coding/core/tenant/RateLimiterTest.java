package com.glmapper.coding.core.tenant;

import com.glmapper.coding.core.config.PiAgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RateLimiterTest {

    private PiAgentProperties propsWithRpm(int rpm) {
        return new PiAgentProperties(
                List.of(), null, null, null, null, List.of(),
                new PiAgentProperties.RateLimitConfig(true, rpm),
                null, null, null
        );
    }

    @Test
    void allowsRequestsWithinLimit() {
        RateLimiter limiter = new RateLimiter(propsWithRpm(5));
        for (int i = 0; i < 5; i++) {
            assertTrue(limiter.tryAcquire("test-ns"), "Request " + i + " should be allowed");
        }
    }

    @Test
    void rejectsRequestsOverLimit() {
        RateLimiter limiter = new RateLimiter(propsWithRpm(3));
        assertTrue(limiter.tryAcquire("test-ns"));
        assertTrue(limiter.tryAcquire("test-ns"));
        assertTrue(limiter.tryAcquire("test-ns"));
        assertFalse(limiter.tryAcquire("test-ns"), "4th request should be rejected");
    }

    @Test
    void differentNamespacesAreIndependent() {
        RateLimiter limiter = new RateLimiter(propsWithRpm(2));
        assertTrue(limiter.tryAcquire("ns-a"));
        assertTrue(limiter.tryAcquire("ns-a"));
        assertFalse(limiter.tryAcquire("ns-a"));
        // ns-b should still have capacity
        assertTrue(limiter.tryAcquire("ns-b"));
    }

    @Test
    void disabledRateLimitAlwaysAllows() {
        PiAgentProperties props = new PiAgentProperties(
                List.of(), null, null, null, null, List.of(),
                new PiAgentProperties.RateLimitConfig(false, 1),
                null, null, null
        );
        RateLimiter limiter = new RateLimiter(props);
        for (int i = 0; i < 100; i++) {
            assertTrue(limiter.tryAcquire("test-ns"));
        }
    }

    @Test
    void nullRateLimitConfigAlwaysAllows() {
        PiAgentProperties props = new PiAgentProperties(
                List.of(), null, null, null, null, List.of(),
                null, null, null, null
        );
        RateLimiter limiter = new RateLimiter(props);
        assertTrue(limiter.tryAcquire("test-ns"));
    }
}
