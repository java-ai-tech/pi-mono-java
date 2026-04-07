package com.glmapper.ai.provider.springai;

import com.glmapper.ai.api.*;
import com.glmapper.ai.spi.ApiProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.Objects;

public final class SpringAiChatModelProvider implements ApiProvider {
    private final String apiName;
    private final ChatModel chatModel;

    public SpringAiChatModelProvider(String apiName, ChatModel chatModel) {
        this.apiName = Objects.requireNonNull(apiName);
        this.chatModel = Objects.requireNonNull(chatModel);
    }

    @Override
    public String api() {
        return apiName;
    }

    @Override
    public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
        DefaultAssistantMessageEventStream stream = new DefaultAssistantMessageEventStream();
        try {
            Prompt prompt = PromptConverter.convert(model, context, options);
            Flux<org.springframework.ai.chat.model.ChatResponse> flux = chatModel.stream(prompt);
            FluxToEventStreamBridge.bridge(flux, stream, model);
        } catch (Exception ex) {
            AssistantMessage error = new AssistantMessage(
                    java.util.List.of(new TextContent("", null)),
                    model.api(), model.provider(), model.id(),
                    Usage.empty(), StopReason.ERROR, ex.getMessage(), null,
                    System.currentTimeMillis()
            );
            stream.push(new AssistantMessageEvent.Error(StopReason.ERROR, error));
        }
        return stream;
    }
}
