package com.glmapper.ai.api;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.SubmissionPublisher;

public final class DefaultAssistantMessageEventStream implements AssistantMessageEventStream, AutoCloseable {
    private final SubmissionPublisher<AssistantMessageEvent> publisher;
    private final CompletableFuture<AssistantMessage> result;

    public DefaultAssistantMessageEventStream() {
        this.publisher = new SubmissionPublisher<>();
        this.result = new CompletableFuture<>();
    }

    @Override
    public Flow.Publisher<AssistantMessageEvent> publisher() {
        return publisher;
    }

    @Override
    public CompletableFuture<AssistantMessage> result() {
        return result;
    }

    public void push(AssistantMessageEvent event) {
        publisher.submit(event);
        if (event instanceof AssistantMessageEvent.Done done) {
            result.complete(done.message());
            publisher.close();
        } else if (event instanceof AssistantMessageEvent.Error error) {
            result.complete(error.error());
            publisher.close();
        }
    }

    public void complete(AssistantMessage message) {
        result.complete(message);
        publisher.close();
    }

    public void completeExceptionally(Throwable throwable) {
        result.completeExceptionally(throwable);
        publisher.closeExceptionally(throwable);
    }

    @Override
    public void close() {
        publisher.close();
    }
}
