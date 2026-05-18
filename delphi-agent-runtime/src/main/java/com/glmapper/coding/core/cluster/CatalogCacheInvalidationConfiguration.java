package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.catalog.ResourceCatalogService;
import com.glmapper.coding.core.catalog.SkillsResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class CatalogCacheInvalidationConfiguration {

    @Bean
    public CacheInvalidationSubscriber cacheInvalidationSubscriber(
            ObjectMapper objectMapper,
            ClusterNodeIdentity nodeIdentity,
            ResourceCatalogService resourceCatalogService,
            SkillsResolver skillsResolver) {
        return new CacheInvalidationSubscriber(objectMapper, nodeIdentity,
                resourceCatalogService, skillsResolver);
    }

    @Bean
    public RedisMessageListenerContainer cacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory,
            CacheInvalidationSubscriber subscriber,
            ClusterKeyRegistry keyRegistry) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(subscriber, new ChannelTopic(keyRegistry.cacheInvalidateChannel()));
        return container;
    }
}
