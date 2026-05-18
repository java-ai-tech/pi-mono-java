package com.glmapper.coding.core.cluster;

import com.glmapper.coding.core.config.PiAgentProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Configuration
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class ClusterAutoConfiguration {
    private static final Logger log = LoggerFactory.getLogger(ClusterAutoConfiguration.class);
    private static final Duration NODE_REGISTRY_TTL = Duration.ofSeconds(30);
    private static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(10);

    private final RedisConnectionFactory connectionFactory;
    private final StringRedisTemplate redisTemplate;
    private final ClusterNodeIdentity nodeIdentity;
    private final ClusterKeyRegistry keyRegistry;
    private final java.util.concurrent.ScheduledExecutorService heartbeatScheduler =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cluster-node-heartbeat");
                t.setDaemon(true);
                return t;
            });

    public ClusterAutoConfiguration(
            RedisConnectionFactory connectionFactory,
            StringRedisTemplate redisTemplate,
            ClusterNodeIdentity nodeIdentity,
            ClusterKeyRegistry keyRegistry,
            PiAgentProperties properties
    ) {
        this.connectionFactory = connectionFactory;
        this.redisTemplate = redisTemplate;
        this.nodeIdentity = nodeIdentity;
        this.keyRegistry = keyRegistry;
        validateClusterConfig(properties);
    }

    @PostConstruct
    public void initialize() {
        verifyRedisConnectivity();
        registerNode();
        startHeartbeat();
        log.info("Cluster mode enabled: nodeId={}, keyPrefix={}",
                nodeIdentity.getNodeId(), keyRegistry.getPrefix());
    }

    @PreDestroy
    public void shutdown() {
        heartbeatScheduler.shutdownNow();
        try {
            redisTemplate.delete(keyRegistry.nodeRegistryKey(nodeIdentity.getNodeId()));
        } catch (Exception e) {
            log.warn("Failed to remove node registry key during shutdown", e);
        }
    }

    private void validateClusterConfig(PiAgentProperties properties) {
        if (nodeIdentity.getNodeId() == null || nodeIdentity.getNodeId().isBlank()) {
            throw new IllegalStateException("Cluster mode enabled but nodeId is empty");
        }
        if (properties.cluster() == null) {
            throw new IllegalStateException("Cluster mode enabled but pi.cluster config is missing");
        }
    }

    private void verifyRedisConnectivity() {
        try (RedisConnection connection = connectionFactory.getConnection()) {
            String pong = connection.ping();
            if (pong == null) {
                throw new IllegalStateException("Redis ping returned null");
            }
            log.info("Redis connectivity verified: ping={}", pong);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Cluster mode enabled but Redis is not reachable", e);
        }
    }

    private void registerNode() {
        String key = keyRegistry.nodeRegistryKey(nodeIdentity.getNodeId());
        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                key, Long.toString(System.currentTimeMillis()), NODE_REGISTRY_TTL);
        if (Boolean.FALSE.equals(acquired)) {
            throw new IllegalStateException(
                    "Duplicate nodeId detected in cluster: " + nodeIdentity.getNodeId());
        }
    }

    private void startHeartbeat() {
        String key = keyRegistry.nodeRegistryKey(nodeIdentity.getNodeId());
        heartbeatScheduler.scheduleAtFixedRate(() -> {
            try {
                redisTemplate.opsForValue().set(
                        key, Long.toString(System.currentTimeMillis()), NODE_REGISTRY_TTL);
            } catch (Exception e) {
                log.warn("Node heartbeat renewal failed", e);
            }
        }, HEARTBEAT_INTERVAL.toMillis(), HEARTBEAT_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }
}
