package com.glmapper.ai.spi;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class ApiProviderRegistry {
    private final Map<String, ApiProvider> providers = new ConcurrentHashMap<>();

    public void register(ApiProvider provider) {
        providers.put(provider.api(), provider);
    }

    public Optional<ApiProvider> get(String api) {
        return Optional.ofNullable(providers.get(api));
    }

    public Collection<ApiProvider> all() {
        return providers.values();
    }

    public void unregister(String api) {
        providers.remove(api);
    }

    public void clear() {
        providers.clear();
    }
}
