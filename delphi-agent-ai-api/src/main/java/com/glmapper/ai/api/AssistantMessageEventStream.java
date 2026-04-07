package com.glmapper.ai.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

public interface AssistantMessageEventStream {
    Flow.Publisher<AssistantMessageEvent> publisher();

    CompletableFuture<AssistantMessage> result();
}
