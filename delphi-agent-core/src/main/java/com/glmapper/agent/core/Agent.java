package com.glmapper.agent.core;

import com.glmapper.ai.api.*;
import com.glmapper.ai.spi.AiRuntime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

public final class Agent {
    private final AiRuntime aiRuntime;
    private final AgentState state;
    private final AgentOptions options;
    private final List<Consumer<AgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private final Queue<AgentMessage> steeringQueue = new ArrayDeque<>();
    private final Queue<AgentMessage> followUpQueue = new ArrayDeque<>();

    private volatile CompletableFuture<Void> runningPrompt;
    private volatile boolean aborted;
    private volatile QueueMode steeringMode = QueueMode.ALL;
    private volatile QueueMode followUpMode = QueueMode.ALL;

    public Agent(AiRuntime aiRuntime, Model model, AgentOptions options) {
        this.aiRuntime = Objects.requireNonNull(aiRuntime, "aiRuntime");
        this.state = new AgentState(Objects.requireNonNull(model, "model"));
        this.options = options == null ? AgentOptions.defaults() : options;
    }

    public AgentState state() {
        return state;
    }

    public AutoCloseable subscribe(Consumer<AgentEvent> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public synchronized void steer(AgentMessage message) {
        steeringQueue.add(message);
    }

    public synchronized void followUp(AgentMessage message) {
        followUpQueue.add(message);
    }

    public synchronized CompletableFuture<Void> prompt(String text) {
        return prompt(List.of(new AgentUserMessage(List.of(new TextContent(text, null)), System.currentTimeMillis())));
    }

    public synchronized CompletableFuture<Void> prompt(List<AgentMessage> prompts) {
        if (state.streaming()) {
            throw new IllegalStateException("Agent is already processing a prompt");
        }
        runningPrompt = runLoop(prompts, false);
        return runningPrompt;
    }

    public synchronized CompletableFuture<Void> cont() {
        if (state.streaming()) {
            throw new IllegalStateException("Agent is already processing");
        }
        var messages = state.messages();
        if (messages.isEmpty()) {
            throw new IllegalStateException("No messages to continue from");
        }
        AgentMessage last = messages.get(messages.size() - 1);
        if ("assistant".equals(last.role())) {
            var queuedSteering = dequeueSteering(steeringMode == QueueMode.ONE_AT_A_TIME);
            if (!queuedSteering.isEmpty()) {
                runningPrompt = runLoop(queuedSteering, true);
                return runningPrompt;
            }
            var queuedFollowUp = dequeueFollowUp(followUpMode == QueueMode.ONE_AT_A_TIME);
            if (!queuedFollowUp.isEmpty()) {
                runningPrompt = runLoop(queuedFollowUp, false);
                return runningPrompt;
            }
            throw new IllegalStateException("Cannot continue from message role: assistant");
        }
        runningPrompt = runLoop(List.of(), false);
        return runningPrompt;
    }

    public synchronized CompletableFuture<Void> waitForIdle() {
        return runningPrompt == null ? CompletableFuture.completedFuture(null) : runningPrompt;
    }

    public QueueMode steeringMode() {
        return steeringMode;
    }

    public void steeringMode(QueueMode mode) {
        this.steeringMode = mode == null ? QueueMode.ALL : mode;
    }

    public QueueMode followUpMode() {
        return followUpMode;
    }

    public void followUpMode(QueueMode mode) {
        this.followUpMode = mode == null ? QueueMode.ALL : mode;
    }

    public synchronized void abort() {
        this.aborted = true;
    }

    private CompletableFuture<Void> runLoop(List<AgentMessage> prompts, boolean skipInitialSteeringPoll) {
        return CompletableFuture.runAsync(() -> {
            try {
                state.streaming(true);
                state.error(null);
                emit(new AgentEvent.AgentStart());
                emit(new AgentEvent.TurnStart());

                List<AgentMessage> newMessages = new ArrayList<>();
                List<AgentMessage> contextMessages = state.messages();

                for (AgentMessage prompt : prompts) {
                    emit(new AgentEvent.MessageStart(prompt));
                    emit(new AgentEvent.MessageEnd(prompt));
                    contextMessages.add(prompt);
                    newMessages.add(prompt);
                    state.appendMessage(prompt);
                }

                List<AgentMessage> pending = skipInitialSteeringPoll ? List.of() : getSteeringMessages();
                boolean firstTurn = true;

                while (true) {
                    boolean hasMoreToolCalls = true;
                    while (hasMoreToolCalls || !pending.isEmpty()) {
                        if (!firstTurn) {
                            emit(new AgentEvent.TurnStart());
                        } else {
                            firstTurn = false;
                        }

                        if (!pending.isEmpty()) {
                            for (AgentMessage message : pending) {
                                emit(new AgentEvent.MessageStart(message));
                                emit(new AgentEvent.MessageEnd(message));
                                contextMessages.add(message);
                                state.appendMessage(message);
                                newMessages.add(message);
                            }
                            pending = List.of();
                        }

                        AgentAssistantMessage assistant = streamAssistantResponse(contextMessages);
                        newMessages.add(assistant);

                        List<ToolCallContent> toolCalls = assistant.content().stream()
                                .filter(ToolCallContent.class::isInstance)
                                .map(ToolCallContent.class::cast)
                                .toList();
                        hasMoreToolCalls = !toolCalls.isEmpty();

                        List<AgentToolResultMessage> toolResults = new ArrayList<>();
                        if (hasMoreToolCalls) {
                            toolResults.addAll(executeToolCalls(contextMessages, assistant, toolCalls));
                            for (AgentToolResultMessage result : toolResults) {
                                contextMessages.add(result);
                                state.appendMessage(result);
                                newMessages.add(result);
                            }
                        }

                        emit(new AgentEvent.TurnEnd(assistant, toolResults));
                        pending = getSteeringMessages();

                        if (assistant.stopReason() == StopReason.ERROR || assistant.stopReason() == StopReason.ABORTED) {
                            emit(new AgentEvent.AgentEnd(newMessages));
                            return;
                        }
                    }

                    List<AgentMessage> followUps = getFollowUpMessages();
                    if (followUps.isEmpty()) {
                        break;
                    }
                    pending = followUps;
                }

                emit(new AgentEvent.AgentEnd(newMessages));
            } catch (Exception ex) {
                state.error(ex.getMessage());
                AgentAssistantMessage errorMessage = new AgentAssistantMessage(
                        List.of(new TextContent("", null)),
                        state.model().api(),
                        state.model().provider(),
                        state.model().id(),
                        Usage.empty(),
                        aborted ? StopReason.ABORTED : StopReason.ERROR,
                        ex.getMessage(),
                        null,
                        System.currentTimeMillis()
                );
                state.appendMessage(errorMessage);
                emit(new AgentEvent.AgentEnd(List.of(errorMessage)));
            } finally {
                state.streaming(false);
                state.streamMessage(null);
                state.pendingToolCalls(Set.of());
                aborted = false;
            }
        });
    }

    private AgentAssistantMessage streamAssistantResponse(List<AgentMessage> contextMessages) throws InterruptedException {
        List<AgentMessage> transformed = options.transformContext()
                .apply(contextMessages, new java.util.concurrent.CancellationException("transform-context"))
                .join();

        List<Message> llmMessages = options.convertToLlm().apply(transformed);
        Context llmContext = new Context(state.systemPrompt(), llmMessages, state.tools().stream()
                .map(tool -> new ToolDefinition(tool.name(), tool.description(), tool.parametersSchema()))
                .toList());

        StreamOptions baseOptions = options.streamOptions();
        String apiKey = options.apiKeyResolver().apply(state.model().provider()).join();
        StreamOptions requestOptions = new StreamOptions(
                baseOptions.temperature(),
                baseOptions.maxTokens(),
                apiKey != null ? apiKey : baseOptions.apiKey(),
                baseOptions.transport(),
                baseOptions.cacheRetention(),
                baseOptions.sessionId(),
                baseOptions.maxRetryDelayMs(),
                baseOptions.headers(),
                baseOptions.metadata(),
                state.thinkingLevel() == ThinkingLevel.OFF ? null : state.thinkingLevel(),
                baseOptions.thinkingBudgets()
        );

        AssistantMessageEventStream response = aiRuntime.streamSimple(state.model(), llmContext, requestOptions);
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<AgentAssistantMessage> partialRef = new AtomicReference<>();

        response.publisher().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(AssistantMessageEvent item) {
                if (item instanceof AssistantMessageEvent.Start start) {
                    AgentAssistantMessage partial = AgentAdapters.toAgentAssistant(start.partial());
                    partialRef.set(partial);
                    state.streamMessage(partial);
                    emit(new AgentEvent.MessageStart(partial));
                    return;
                }

                if (item instanceof AssistantMessageEvent.Done doneEvent) {
                    AgentAssistantMessage finalMessage = AgentAdapters.toAgentAssistant(doneEvent.message());
                    state.streamMessage(null);
                    state.appendMessage(finalMessage);
                    contextMessages.add(finalMessage);
                    emit(new AgentEvent.MessageEnd(finalMessage));
                    partialRef.set(finalMessage);
                    return;
                }

                if (item instanceof AssistantMessageEvent.Error errorEvent) {
                    AgentAssistantMessage finalMessage = AgentAdapters.toAgentAssistant(errorEvent.error());
                    state.streamMessage(null);
                    state.appendMessage(finalMessage);
                    contextMessages.add(finalMessage);
                    emit(new AgentEvent.MessageEnd(finalMessage));
                    partialRef.set(finalMessage);
                    return;
                }

                AgentAssistantMessage partial = extractPartial(item, partialRef.get());
                if (partial != null) {
                    partialRef.set(partial);
                    state.streamMessage(partial);
                    emit(new AgentEvent.MessageUpdate(partial, item));
                }
            }

            @Override
            public void onError(Throwable throwable) {
                done.countDown();
            }

            @Override
            public void onComplete() {
                done.countDown();
            }
        });

        AssistantMessage finalMessage = response.result().join();
        done.await();
        return AgentAdapters.toAgentAssistant(finalMessage);
    }

    private AgentAssistantMessage extractPartial(AssistantMessageEvent event, AgentAssistantMessage fallback) {
        if (event instanceof AssistantMessageEvent.TextStart textStart) {
            return AgentAdapters.toAgentAssistant(textStart.partial());
        }
        if (event instanceof AssistantMessageEvent.TextDelta textDelta) {
            return AgentAdapters.toAgentAssistant(textDelta.partial());
        }
        if (event instanceof AssistantMessageEvent.TextEnd textEnd) {
            return AgentAdapters.toAgentAssistant(textEnd.partial());
        }
        if (event instanceof AssistantMessageEvent.ThinkingStart thinkingStart) {
            return AgentAdapters.toAgentAssistant(thinkingStart.partial());
        }
        if (event instanceof AssistantMessageEvent.ThinkingDelta thinkingDelta) {
            return AgentAdapters.toAgentAssistant(thinkingDelta.partial());
        }
        if (event instanceof AssistantMessageEvent.ThinkingEnd thinkingEnd) {
            return AgentAdapters.toAgentAssistant(thinkingEnd.partial());
        }
        if (event instanceof AssistantMessageEvent.ToolCallStart toolCallStart) {
            return AgentAdapters.toAgentAssistant(toolCallStart.partial());
        }
        if (event instanceof AssistantMessageEvent.ToolCallDelta toolCallDelta) {
            return AgentAdapters.toAgentAssistant(toolCallDelta.partial());
        }
        if (event instanceof AssistantMessageEvent.ToolCallEnd toolCallEnd) {
            return AgentAdapters.toAgentAssistant(toolCallEnd.partial());
        }
        return fallback;
    }

    private List<AgentToolResultMessage> executeToolCalls(
            List<AgentMessage> currentContext,
            AgentAssistantMessage assistant,
            List<ToolCallContent> toolCalls
    ) {
        return options.toolExecutionMode() == ToolExecutionMode.SEQUENTIAL
                ? executeToolCallsSequential(currentContext, assistant, toolCalls)
                : executeToolCallsParallel(currentContext, assistant, toolCalls);
    }

    private List<AgentToolResultMessage> executeToolCallsSequential(
            List<AgentMessage> currentContext,
            AgentAssistantMessage assistant,
            List<ToolCallContent> toolCalls
    ) {
        List<AgentToolResultMessage> results = new ArrayList<>();
        for (ToolCallContent call : toolCalls) {
            results.add(runSingleToolCall(currentContext, assistant, call).join());
        }
        return results;
    }

    private List<AgentToolResultMessage> executeToolCallsParallel(
            List<AgentMessage> currentContext,
            AgentAssistantMessage assistant,
            List<ToolCallContent> toolCalls
    ) {
        List<CompletableFuture<AgentToolResultMessage>> futures = toolCalls.stream()
                .map(call -> runSingleToolCall(currentContext, assistant, call))
                .toList();

        List<AgentToolResultMessage> results = new ArrayList<>(futures.size());
        for (CompletableFuture<AgentToolResultMessage> future : futures) {
            results.add(future.join());
        }
        return results;
    }

    private CompletableFuture<AgentToolResultMessage> runSingleToolCall(
            List<AgentMessage> currentContext,
            AgentAssistantMessage assistant,
            ToolCallContent call
    ) {
        emit(new AgentEvent.ToolExecutionStart(call.id(), call.name(), call.arguments()));

        AgentTool tool = state.tools().stream()
                .filter(candidate -> candidate.name().equals(call.name()))
                .findFirst()
                .orElse(null);

        if (tool == null) {
            AgentToolResult errorResult = AgentToolResult.error("Tool " + call.name() + " not found");
            return CompletableFuture.completedFuture(emitToolResult(call, errorResult, true));
        }

        Map<String, Object> validatedArgs;
        try {
            validatedArgs = ToolArgumentValidator.validate(tool.parametersSchema(), call.arguments());
        } catch (Exception validationError) {
            AgentToolResult errorResult = AgentToolResult.error(validationError.getMessage());
            return CompletableFuture.completedFuture(emitToolResult(call, errorResult, true));
        }

        AgentContext context = new AgentContext(state.systemPrompt(), currentContext, state.tools());
        BeforeToolCallResult before = options.beforeToolCall()
                .apply(new BeforeToolCallContext(assistant, call, validatedArgs, context),
                        new java.util.concurrent.CancellationException("before-tool-call"))
                .join();
        if (before != null && before.block()) {
            AgentToolResult blocked = AgentToolResult.error(before.reason() != null ? before.reason() : "Tool execution blocked");
            return CompletableFuture.completedFuture(emitToolResult(call, blocked, true));
        }

        Set<String> pending = new LinkedHashSet<>(state.pendingToolCalls());
        pending.add(call.id());
        state.pendingToolCalls(pending);

        return tool.execute(call.id(), validatedArgs, partial ->
                emit(new AgentEvent.ToolExecutionUpdate(call.id(), call.name(), validatedArgs, partial)),
                new java.util.concurrent.CancellationException("tool-cancel"))
                .handle((result, throwable) -> {
                    AgentToolResult finalResult;
                    boolean isError;
                    if (throwable != null) {
                        finalResult = AgentToolResult.error(throwable.getMessage());
                        isError = true;
                    } else {
                        finalResult = result;
                        isError = false;
                    }

                    AfterToolCallResult after = options.afterToolCall()
                            .apply(new AfterToolCallContext(assistant, call, validatedArgs, finalResult, isError, context),
                                    new java.util.concurrent.CancellationException("after-tool-call"))
                            .join();

                    if (after != null) {
                        finalResult = new AgentToolResult(
                                after.content() != null ? after.content() : finalResult.content(),
                                after.details() != null ? after.details() : finalResult.details()
                        );
                        if (after.isError() != null) {
                            isError = after.isError();
                        }
                    }

                    AgentToolResultMessage toolResult = emitToolResult(call, finalResult, isError);
                    Set<String> pendingAfter = new LinkedHashSet<>(state.pendingToolCalls());
                    pendingAfter.remove(call.id());
                    state.pendingToolCalls(pendingAfter);
                    return toolResult;
                });
    }

    private AgentToolResultMessage emitToolResult(ToolCallContent call, AgentToolResult result, boolean isError) {
        emit(new AgentEvent.ToolExecutionEnd(call.id(), call.name(), result, isError));
        AgentToolResultMessage message = new AgentToolResultMessage(
                call.id(),
                call.name(),
                result.content(),
                result.details(),
                isError,
                System.currentTimeMillis()
        );
        emit(new AgentEvent.MessageStart(message));
        emit(new AgentEvent.MessageEnd(message));
        return message;
    }

    private List<AgentMessage> getSteeringMessages() {
        List<AgentMessage> queued = dequeueSteering(steeringMode == QueueMode.ONE_AT_A_TIME);
        if (!queued.isEmpty()) {
            return queued;
        }
        return options.steeringMessagesSupplier().get().join();
    }

    private List<AgentMessage> getFollowUpMessages() {
        List<AgentMessage> queued = dequeueFollowUp(followUpMode == QueueMode.ONE_AT_A_TIME);
        if (!queued.isEmpty()) {
            return queued;
        }
        return options.followUpMessagesSupplier().get().join();
    }

    private synchronized List<AgentMessage> dequeueSteering(boolean oneAtATime) {
        if (steeringQueue.isEmpty()) {
            return List.of();
        }
        if (oneAtATime) {
            return List.of(steeringQueue.poll());
        }
        List<AgentMessage> all = new ArrayList<>(steeringQueue);
        steeringQueue.clear();
        return all;
    }

    private synchronized List<AgentMessage> dequeueFollowUp(boolean oneAtATime) {
        if (followUpQueue.isEmpty()) {
            return List.of();
        }
        if (oneAtATime) {
            return List.of(followUpQueue.poll());
        }
        List<AgentMessage> all = new ArrayList<>(followUpQueue);
        followUpQueue.clear();
        return all;
    }

    private void emit(AgentEvent event) {
        for (Consumer<AgentEvent> listener : listeners) {
            listener.accept(event);
        }
    }
}
