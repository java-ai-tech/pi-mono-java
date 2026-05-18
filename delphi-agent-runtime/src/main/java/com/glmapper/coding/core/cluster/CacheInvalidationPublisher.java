package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class CacheInvalidationPublisher {
    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final ObjectMapper objectMapper;

    public CacheInvalidationPublisher(StringRedisTemplate redisTemplate,
                                      ClusterKeyRegistry keyRegistry,
                                      ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.objectMapper = objectMapper;
    }

    public void publish(CatalogCacheInvalidationMessage message) {
        try {
            String json = objectMapper.writeValueAsString(message);
            redisTemplate.convertAndSend(keyRegistry.cacheInvalidateChannel(), json);
        } catch (Exception e) {
            log.warn("Failed to publish catalog cache invalidation: scope={}, namespace={}",
                    message.scope(), message.namespace(), e);
        }
    }
}
