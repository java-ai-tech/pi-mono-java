package com.glmapper.coding.core.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.coding.core.catalog.ResourceCatalogService;
import com.glmapper.coding.core.catalog.SkillsResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

public class CacheInvalidationSubscriber implements MessageListener {
    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationSubscriber.class);

    private final ObjectMapper objectMapper;
    private final ClusterNodeIdentity nodeIdentity;
    private final ResourceCatalogService resourceCatalogService;
    private final SkillsResolver skillsResolver;

    public CacheInvalidationSubscriber(ObjectMapper objectMapper,
                                       ClusterNodeIdentity nodeIdentity,
                                       ResourceCatalogService resourceCatalogService,
                                       SkillsResolver skillsResolver) {
        this.objectMapper = objectMapper;
        this.nodeIdentity = nodeIdentity;
        this.resourceCatalogService = resourceCatalogService;
        this.skillsResolver = skillsResolver;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            CatalogCacheInvalidationMessage msg = objectMapper.readValue(
                    message.getBody(), CatalogCacheInvalidationMessage.class);
            if (nodeIdentity.getNodeId().equals(msg.sourceNodeId())) {
                return;
            }
            applyInvalidation(msg);
        } catch (Exception e) {
            log.warn("Failed to process catalog cache invalidation message", e);
        }
    }

    private void applyInvalidation(CatalogCacheInvalidationMessage msg) {
        resourceCatalogService.reload();
        if (CatalogCacheInvalidationMessage.SCOPE_ALL.equals(msg.scope())) {
            skillsResolver.invalidateCache(null);
        } else {
            skillsResolver.invalidateCache(msg.namespace());
        }
        log.info("Applied remote catalog invalidation: scope={}, namespace={}, sourceNodeId={}",
                msg.scope(), msg.namespace(), msg.sourceNodeId());
    }
}
