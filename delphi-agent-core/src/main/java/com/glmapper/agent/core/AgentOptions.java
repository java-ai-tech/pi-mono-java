package com.glmapper.agent.core;

import com.glmapper.ai.api.Message;
import com.glmapper.ai.api.StreamOptions;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

public record AgentOptions(
        Function<List<AgentMessage>, List<Message>> convertToLlm,
        BiFunction<List<AgentMessage>, java.util.concurrent.CancellationException, CompletableFuture<List<AgentMessage>>> transformContext,
        Supplier<CompletableFuture<List<AgentMessage>>> steeringMessagesSupplier,
        Supplier<CompletableFuture<List<AgentMessage>>> followUpMessagesSupplier,
        ToolExecutionMode toolExecutionMode,
        BiFunction<BeforeToolCallContext, java.util.concurrent.CancellationException, CompletableFuture<BeforeToolCallResult>> beforeToolCall,
        BiFunction<AfterToolCallContext, java.util.concurrent.CancellationException, CompletableFuture<AfterToolCallResult>> afterToolCall,
        Function<String, CompletableFuture<String>> apiKeyResolver,
        StreamOptions streamOptions
) {
    public static AgentOptions defaults() {
        return new AgentOptions(
                AgentAdapters::defaultConvertToLlm,
                (messages, ignored) -> CompletableFuture.completedFuture(messages),
                () -> CompletableFuture.completedFuture(List.of()),
                () -> CompletableFuture.completedFuture(List.of()),
                ToolExecutionMode.PARALLEL,
                (context, ignored) -> CompletableFuture.completedFuture(null),
                (context, ignored) -> CompletableFuture.completedFuture(null),
                provider -> CompletableFuture.completedFuture(null),
                new StreamOptions(null, null, null, "sse", "short", null, 60_000, Map.of(), Map.of(), null, Map.of())
        );
    }
}
