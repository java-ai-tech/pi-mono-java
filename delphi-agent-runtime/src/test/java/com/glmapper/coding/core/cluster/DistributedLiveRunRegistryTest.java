package com.glmapper.coding.core.cluster;

import com.glmapper.coding.core.config.PiAgentProperties;
import com.glmapper.coding.core.tenant.TenantQuotaManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DistributedLiveRunRegistryTest {

    private StringRedisTemplate redisTemplate;
    private ZSetOperations<String, String> zSetOperations;
    private DistributedLiveRunRegistry registry;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        PiAgentProperties props = new PiAgentProperties(
                null, null, null, null, null, null, null, null, null, null,
                new PiAgentProperties.ClusterConfig(true, "test-node",
                        new PiAgentProperties.ClusterConfig.RedisConfig("delphi"),
                        null, new PiAgentProperties.ClusterConfig.RunConfig(60_000L), 0)
        );

        registry = new DistributedLiveRunRegistry(
                mock(RunAdmissionController.class),
                mock(RunCommandDispatcher.class),
                redisTemplate,
                new ClusterKeyRegistry(props),
                mock(TenantQuotaManager.class),
                props
        );
    }

    @AfterEach
    void tearDown() {
        registry.shutdown();
    }

    @Test
    void activeCountByTenantCountsOnlyUnexpiredZSetMembers() {
        String key = "delphi:run:tenant-active:ns";
        when(zSetOperations.count(eq(key), anyDouble(), eq(Double.POSITIVE_INFINITY))).thenReturn(2L);

        assertEquals(2, registry.activeCountByTenant("ns"));

        verify(zSetOperations).removeRangeByScore(eq(key), eq(0.0), anyDouble());
        verify(zSetOperations).count(eq(key), anyDouble(), eq(Double.POSITIVE_INFINITY));
    }

    @Test
    void activeCountByUserCountsOnlyUnexpiredZSetMembers() {
        String key = "delphi:run:user-active:ns:user1";
        when(zSetOperations.count(eq(key), anyDouble(), eq(Double.POSITIVE_INFINITY))).thenReturn(1L);

        assertEquals(1, registry.activeCountByUser("ns", "user1"));

        verify(zSetOperations).removeRangeByScore(eq(key), eq(0.0), anyDouble());
        verify(zSetOperations).count(eq(key), anyDouble(), eq(Double.POSITIVE_INFINITY));
    }
}
