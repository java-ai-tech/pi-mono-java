package com.glmapper.coding.http.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.cluster.ClusterKeyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

public class RedisSseEventPublisher {
    private static final Logger log = LoggerFactory.getLogger(RedisSseEventPublisher.class);

    private final StringRedisTemplate redisTemplate;
    private final ClusterKeyRegistry keyRegistry;
    private final ObjectMapper objectMapper;

    public RedisSseEventPublisher(StringRedisTemplate redisTemplate,
                                  ClusterKeyRegistry keyRegistry,
                                  ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.keyRegistry = keyRegistry;
        this.objectMapper = objectMapper;
    }

    public void publish(SseEventEnvelope envelope) {
        try {
            String json = objectMapper.writeValueAsString(envelope);
            redisTemplate.convertAndSend(keyRegistry.sseEventsChannel(), json);
        } catch (Exception e) {
            log.warn("Failed to publish SSE event to Redis: eventId={}", envelope.event().eventId(), e);
        }
    }
}
