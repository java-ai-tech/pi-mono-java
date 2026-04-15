package com.glmapper.coding.http.api.controller;

import com.glmapper.coding.core.catalog.ResourceCatalogService;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.catalog.SkillsResolver;
import com.glmapper.coding.core.service.AgentSessionRuntime;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/catalog")
public class CatalogController {
    private final SkillsResolver skillsResolver;
    private final ResourceCatalogService resourceCatalogService;
    private final AgentSessionRuntime runtime;

    public CatalogController(SkillsResolver skillsResolver,
                             ResourceCatalogService resourceCatalogService,
                             AgentSessionRuntime runtime) {
        this.skillsResolver = skillsResolver;
        this.resourceCatalogService = resourceCatalogService;
        this.runtime = runtime;
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
        return Map.of("status", "ok");
    }
}
