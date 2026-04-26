package com.glmapper.coding.server;

import com.glmapper.ai.api.Model;
import com.glmapper.ai.provider.springai.SpringAiChatModelProvider;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.ai.spi.ApiProvider;
import com.glmapper.ai.spi.ApiProviderRegistry;
import com.glmapper.ai.spi.ModelCatalog;
import com.glmapper.coding.core.config.PiAgentProperties;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AiProviderConfiguration {
    private static final String DEEPSEEK_ANTHROPIC_BASE_URL = "https://api.deepseek.com/anthropic";

    @Bean
    public AnthropicChatModel deepseekChatModel() {
        String apiKey = resolveKey("DEEPSEEK_API_KEY");
        var api = AnthropicApi.builder()
                .baseUrl(DEEPSEEK_ANTHROPIC_BASE_URL)
                .apiKey(apiKey)
                .build();

        // DeepSeek Anthropic 兼容端点的两条强约束：
        // 1. max_tokens 是必填字段（原版 Anthropic API 也是必填，但 Spring AI 不会自动填默认值）
        // 2. 多轮对话中开启 thinking 后必须把 thinking blocks 原样回传，
        //    Spring AI 当前实现不支持，因此显式禁用 thinking
        var options = AnthropicChatOptions.builder()
                .maxTokens(8192)
                .thinking(AnthropicApi.ThinkingType.DISABLED, null)
                .build();

        return AnthropicChatModel.builder()
                .anthropicApi(api)
                .defaultOptions(options)
                .build();
    }

    @Bean
    public ApiProvider deepseekProvider(@Qualifier("deepseekChatModel") AnthropicChatModel deepseekChatModel) {
        return new SpringAiChatModelProvider("spring-ai-deepseek", deepseekChatModel);
    }

    @Bean
    public ApiProviderRegistry apiProviderRegistry(List<ApiProvider> providers) {
        ApiProviderRegistry registry = new ApiProviderRegistry();
        providers.forEach(registry::register);
        return registry;
    }

    @Bean
    public ModelCatalog modelCatalog(PiAgentProperties properties) {
        ModelCatalog catalog = new ModelCatalog();
        for (PiAgentProperties.ModelConfig m : properties.models()) {
            catalog.register(new Model(
                    m.id(), m.name(), m.api(), m.provider(), m.baseUrl(),
                    m.reasoning(), m.input(),
                    new Model.CostModel(0, 0, 0, 0),
                    m.contextWindow(), m.maxTokens()
            ));
        }
        return catalog;
    }

    @Bean
    public AiRuntime aiRuntime(ApiProviderRegistry registry) {
        return new AiRuntime(registry);
    }

    private static String resolveKey(String name) {
        String value = System.getenv(name);
        if (value != null && !value.isBlank()) return value;
        value = System.getProperty(name);
        return value != null ? value : "";
    }
}
