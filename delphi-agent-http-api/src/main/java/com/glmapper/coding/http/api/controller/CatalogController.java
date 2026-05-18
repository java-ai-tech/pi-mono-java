package com.glmapper.coding.http.api.controller;

import com.glmapper.coding.core.catalog.ResourceCatalogService;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.catalog.SkillsResolver;
import com.glmapper.coding.core.cluster.CacheInvalidationPublisher;
import com.glmapper.coding.core.cluster.CatalogCacheInvalidationMessage;
import com.glmapper.coding.core.cluster.ClusterNodeIdentity;
import com.glmapper.coding.core.service.AgentSessionRuntime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final SkillsResolver skillsResolver;
    private final ResourceCatalogService resourceCatalogService;
    private final AgentSessionRuntime runtime;
    private final ClusterNodeIdentity nodeIdentity;
    private final ObjectProvider<CacheInvalidationPublisher> publisherProvider;

    public CatalogController(SkillsResolver skillsResolver,
                             ResourceCatalogService resourceCatalogService,
                             AgentSessionRuntime runtime,
                             ClusterNodeIdentity nodeIdentity,
                             ObjectProvider<CacheInvalidationPublisher> publisherProvider) {
        this.skillsResolver = skillsResolver;
        this.resourceCatalogService = resourceCatalogService;
        this.runtime = runtime;
        this.nodeIdentity = nodeIdentity;
        this.publisherProvider = publisherProvider;
    }

    @GetMapping("/skills")
    public List<SkillInfo> skills(@RequestParam String namespace) {
        return skillsResolver.resolveSkills(namespace);
    }

    @GetMapping("/skills/{name}")
    public SkillInfo skill(@RequestParam String namespace, @PathVariable String name) {
        return skillsResolver.resolveSkill(namespace, name)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + name));
    }

    @GetMapping("/prompts")
    public Object prompts() {
        return resourceCatalogService.prompts();
    }

    @GetMapping("/resources")
    public Object resources() {
        return resourceCatalogService.resources();
    }

    @GetMapping("/models")
    public Object models(@RequestParam(required = false) String provider) {
        if (provider == null || provider.isBlank()) {
            return runtime.availableModels();
        }
        return runtime.availableModels(provider);
    }

    @PostMapping("/reload")
    public Map<String, Object> reload(@RequestParam(required = false) String namespace) {
        resourceCatalogService.reload();
        skillsResolver.invalidateCache(namespace);

        CacheInvalidationPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher != null) {
            publisher.publish(new CatalogCacheInvalidationMessage(
                    namespace == null ? CatalogCacheInvalidationMessage.SCOPE_ALL
                            : CatalogCacheInvalidationMessage.SCOPE_NAMESPACE,
                    namespace,
                    "manual_reload",
                    nodeIdentity.getNodeId(),
                    Instant.now()));
        }
        return Map.of("status", "ok");
    }
}
