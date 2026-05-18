package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.runtime.subagent.SubagentRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@ConditionalOnProperty(prefix = "pi.cluster", name = "enabled", havingValue = "true")
public class SubagentCommandConfiguration {

    @Bean
    public SubagentCommandListener subagentCommandListener(ObjectMapper objectMapper,
                                                            SubagentRegistry subagentRegistry) {
        return new SubagentCommandListener(objectMapper, subagentRegistry);
    }

    @Bean
    public RedisMessageListenerContainer subagentCommandListenerContainer(
            RedisConnectionFactory connectionFactory,
            SubagentCommandListener listener,
            ClusterKeyRegistry keyRegistry,
            ClusterNodeIdentity nodeIdentity) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        String channel = keyRegistry.subagentCommandsKey(nodeIdentity.getNodeId());
        container.addMessageListener(listener, new ChannelTopic(channel));
        return container;
    }
}
