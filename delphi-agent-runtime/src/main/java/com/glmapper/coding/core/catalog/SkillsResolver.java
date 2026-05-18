package com.glmapper.coding.core.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SkillsResolver {
    private final ResourceCatalogService catalogService;
    private final Map<String, TtlEntry<List<SkillInfo>>> namespaceCache = new ConcurrentHashMap<>();
    private final long cacheTtlMillis;

    public SkillsResolver(ResourceCatalogService catalogService,
                          @Value("${pi.catalog.cache-ttl-seconds:60}") long cacheTtlSeconds) {
        this.catalogService = catalogService;
        this.cacheTtlMillis = cacheTtlSeconds * 1000L;
    }

    public List<SkillInfo> resolveSkills(String namespace) {
        TtlEntry<List<SkillInfo>> entry = namespaceCache.get(namespace);
        if (entry != null && !entry.isExpired()) {
            return entry.value();
        }
        List<SkillInfo> merged = loadMerged(namespace);
        namespaceCache.put(namespace, new TtlEntry<>(merged, cacheTtlMillis));
        return merged;
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

    private List<SkillInfo> loadMerged(String namespace) {
        Map<String, SkillInfo> merged = new LinkedHashMap<>();
        for (SkillInfo skill : catalogService.skillsByScope("public")) {
            merged.put(skill.name(), skill);
        }
        for (SkillInfo skill : catalogService.skillsByScope("namespaces/" + namespace)) {
            merged.put(skill.name(), skill);
        }
        return new ArrayList<>(merged.values());
    }
}
