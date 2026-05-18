package com.glmapper.coding.http.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.cluster.ClusterKeyRegistry;
import com.glmapper.coding.core.cluster.ClusterNodeIdentity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class SseRedisConfiguration {

    @Bean
    public RedisSseEventPublisher redisSseEventPublisher(StringRedisTemplate redisTemplate,
                                                         ClusterKeyRegistry keyRegistry,
                                                         ObjectMapper objectMapper) {
        return new RedisSseEventPublisher(redisTemplate, keyRegistry, objectMapper);
    }

    @Bean
    public RedisSseEventSubscriber redisSseEventSubscriber(SessionEventBroker broker,
                                                            ClusterNodeIdentity nodeIdentity,
                                                            ObjectMapper objectMapper) {
        return new RedisSseEventSubscriber(broker, nodeIdentity, objectMapper);
    }

    @Bean
    public RedisMessageListenerContainer sseRedisListenerContainer(
            RedisConnectionFactory connectionFactory,
            RedisSseEventSubscriber subscriber,
            ClusterKeyRegistry keyRegistry) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(keyRegistry.sseEventsChannel()));
        return container;
    }
}
