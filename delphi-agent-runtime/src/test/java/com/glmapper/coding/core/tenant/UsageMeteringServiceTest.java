package com.glmapper.coding.core.tenant;

import com.glmapper.coding.core.cluster.ClusterKeyRegistry;
import com.glmapper.coding.core.config.PiAgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageMeteringServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;
    private TestUsageMeteringService service;
    private PiAgentProperties props;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        ObjectProvider<StringRedisTemplate> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisTemplate);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        props = new PiAgentProperties(
                null, null, null, null, null, null, null, null, null,
                new PiAgentProperties.MeteringConfig(true, 30),
                new PiAgentProperties.ClusterConfig(true, "test-node",
                        new PiAgentProperties.ClusterConfig.RedisConfig("delphi"),
                        null, null, 0)
        );

        service = new TestUsageMeteringService(props, redisProvider);
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushFromRedisFlushesAllUsageDatesByKeyDate() {
        Map<String, Long> redisValues = Map.of(
                "delphi:usage:20260518:ns:inputTokens", 10L,
                "delphi:usage:20260518:ns:requests", 1L,
                "delphi:usage:20260519:ns:outputTokens", 20L,
                "delphi:usage:20260519:ns:toolCalls", 2L
        );
        when(redisTemplate.keys("delphi:usage:*")).thenReturn(redisValues.keySet());
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenAnswer(invocation -> {
            List<String> keys = invocation.getArgument(1);
            return redisValues.get(keys.get(0));
        });

        service.flush();

        Map<LocalDate, long[]> updatesByDate = new HashMap<>();
        service.upserts.forEach((bucket, deltas) -> updatesByDate.put(bucket.date(), deltas));

        assertEquals(10L, updatesByDate.get(LocalDate.parse("2026-05-18"))[0]);
        assertEquals(1L, updatesByDate.get(LocalDate.parse("2026-05-18"))[2]);
        assertEquals(20L, updatesByDate.get(LocalDate.parse("2026-05-19"))[1]);
        assertEquals(2L, updatesByDate.get(LocalDate.parse("2026-05-19"))[3]);
    }

    @Test
    @SuppressWarnings("unchecked")
    void flushFromRedisRollsBackToOriginalKeyDateWhenMongoFails() {
        String redisKey = "delphi:usage:20260518:ns:inputTokens";
        when(redisTemplate.keys("delphi:usage:*")).thenReturn(Set.of(redisKey));
        when(redisTemplate.execute(any(DefaultRedisScript.class), anyList())).thenReturn(10L);
        service.failUpsert = true;

        service.flush();

        verify(valueOperations).increment("delphi:usage:20260518:ns:inputTokens", 10L);
    }

    private class TestUsageMeteringService extends UsageMeteringService {
        private final Map<Bucket, long[]> upserts = new HashMap<>();
        private boolean failUpsert;

        TestUsageMeteringService(PiAgentProperties props, ObjectProvider<StringRedisTemplate> redisProvider) {
            super(null, props, redisProvider, new ClusterKeyRegistry(props));
        }

        @Override
        protected void upsertUsage(String namespace, LocalDate date, long[] deltas) {
            if (failUpsert) {
                throw new RuntimeException("mongo unavailable");
            }
            upserts.put(new Bucket(date, namespace), Arrays.copyOf(deltas, deltas.length));
        }
    }

    private record Bucket(LocalDate date, String namespace) {
    }
}
