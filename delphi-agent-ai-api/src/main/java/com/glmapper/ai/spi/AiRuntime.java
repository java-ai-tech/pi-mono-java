package com.glmapper.ai.spi;

import com.glmapper.ai.api.AssistantMessage;
import com.glmapper.ai.api.AssistantMessageEventStream;
import com.glmapper.ai.api.Context;
import com.glmapper.ai.api.Model;
import com.glmapper.ai.api.StreamOptions;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class AiRuntime {
    private final ApiProviderRegistry providerRegistry;

    public AiRuntime(ApiProviderRegistry providerRegistry) {
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
    }

    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        var provider = providerRegistry.get(model.api())
                .orElseThrow(() -> new IllegalArgumentException("No provider registered for api: " + model.api()));
        return provider.stream(model, context, options);
    }

    public AssistantMessageEventStream streamSimple(Model model, Context context, StreamOptions options) {
        var provider = providerRegistry.get(model.api())
                .orElseThrow(() -> new IllegalArgumentException("No provider registered for api: " + model.api()));
        return provider.streamSimple(model, context, options);
    }

    public CompletableFuture<AssistantMessage> complete(Model model, Context context, StreamOptions options) {
        return stream(model, context, options).result();
    }

    public CompletableFuture<AssistantMessage> completeSimple(Model model, Context context, StreamOptions options) {
        return streamSimple(model, context, options).result();
    }
}
