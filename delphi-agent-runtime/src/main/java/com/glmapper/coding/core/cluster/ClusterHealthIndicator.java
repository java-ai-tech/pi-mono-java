package com.glmapper.coding.core.cluster;

import com.glmapper.coding.core.config.PiAgentProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

@Component("cluster")
public class ClusterHealthIndicator implements HealthIndicator {

    private final ClusterNodeIdentity nodeIdentity;
    private final ClusterKeyRegistry keyRegistry;
    private final ObjectProvider<RedisConnectionFactory> redisConnectionFactory;
    private final boolean clusterEnabled;

    public ClusterHealthIndicator(
            ClusterNodeIdentity nodeIdentity,
            ClusterKeyRegistry keyRegistry,
            ObjectProvider<RedisConnectionFactory> redisConnectionFactory,
            PiAgentProperties properties
    ) {
        this.nodeIdentity = nodeIdentity;
        this.keyRegistry = keyRegistry;
        this.redisConnectionFactory = redisConnectionFactory;
        this.clusterEnabled = properties.cluster() != null && properties.cluster().enabled();
    }

    @Override
    public Health health() {
        Health.Builder builder = clusterEnabled ? Health.up() : Health.unknown();
        builder.withDetail("clusterEnabled", clusterEnabled)
                .withDetail("nodeId", nodeIdentity.getNodeId())
                .withDetail("keyPrefix", keyRegistry.getPrefix());

        if (clusterEnabled) {
            RedisConnectionFactory factory = redisConnectionFactory.getIfAvailable();
            if (factory == null) {
                return builder.down().withDetail("redis", "connection factory unavailable").build();
            }
            try (RedisConnection connection = factory.getConnection()) {
                String pong = connection.ping();
                builder.withDetail("redis", pong == null ? "no-response" : pong);
            } catch (Exception e) {
                return builder.down()
                        .withDetail("redis", "unreachable")
                        .withDetail("error", e.getMessage())
                        .build();
            }
        }
        return builder.build();
    }
}
