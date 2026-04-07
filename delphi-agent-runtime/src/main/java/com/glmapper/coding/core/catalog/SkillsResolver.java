package com.glmapper.coding.core.catalog;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillsResolver {
    private final ResourceCatalogService catalogService;
    private final Map<String, List<SkillInfo>> namespaceCache = new ConcurrentHashMap<>();

    public SkillsResolver(ResourceCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    public List<SkillInfo> resolveSkills(String namespace) {
        return namespaceCache.computeIfAbsent(namespace, ns -> {
            Map<String, SkillInfo> merged = new LinkedHashMap<>();

            // Load public skills first
            for (SkillInfo skill : catalogService.skillsByScope("public")) {
                merged.put(skill.name(), skill);
            }

            // Load namespace-specific skills (override public if same name)
            for (SkillInfo skill : catalogService.skillsByScope("namespaces/" + namespace)) {
                merged.put(skill.name(), skill);
            }

            return new ArrayList<>(merged.values());
        });
    }

    public Optional<SkillInfo> resolveSkill(String namespace, String name) {
        return resolveSkills(namespace).stream()
                .filter(s -> s.name().equals(name))
                .findFirst();
    }

    public void invalidateCache(String namespace) {
        if (namespace == null) {
            namespaceCache.clear();
        } else {
            namespaceCache.remove(namespace);
        }
    }
}
