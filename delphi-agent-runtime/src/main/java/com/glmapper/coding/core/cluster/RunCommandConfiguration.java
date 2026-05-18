package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.runtime.LiveRunRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Configuration
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class RunCommandConfiguration {

    @Bean
    public RunCommandListener runCommandListener(ObjectMapper objectMapper,
                                                  DistributedLiveRunRegistry registry) {
        return new RunCommandListener(objectMapper, registry.localHandlesRef());
    }

    @Bean
    public RedisMessageListenerContainer runCommandListenerContainer(
            RedisConnectionFactory connectionFactory,
            RunCommandListener listener,
            ClusterKeyRegistry keyRegistry,
            ClusterNodeIdentity nodeIdentity) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        String channel = keyRegistry.runCommandsKey(nodeIdentity.getNodeId());
        container.addMessageListener(listener, new ChannelTopic(channel));
        return container;
    }
}
