package com.glmapper.ai.spi;

import com.glmapper.ai.api.Model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class ModelCatalog {
    private final Map<String, Map<String, Model>> byProvider = new ConcurrentHashMap<>();

    public void register(Model model) {
        byProvider.computeIfAbsent(model.provider(), p -> new ConcurrentHashMap<>()).put(model.id(), model);
    }

    public Optional<Model> get(String provider, String modelId) {
        return Optional.ofNullable(byProvider.getOrDefault(provider, Map.of()).get(modelId));
    }

    public Collection<Model> getByProvider(String provider) {
        return byProvider.getOrDefault(provider, Map.of()).values();
    }

    public List<Model> getAll() {
        List<Model> models = new ArrayList<>();
        for (Map<String, Model> providerModels : byProvider.values()) {
            models.addAll(providerModels.values());
        }
        return models;
    }

    public Set<String> providers() {
        return byProvider.keySet();
    }
}
