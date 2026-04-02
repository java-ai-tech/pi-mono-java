package com.glmapper.coding.starter;

import com.glmapper.ai.api.Model;
import com.glmapper.ai.provider.springai.SpringAiChatModelProvider;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.ai.spi.ApiProvider;
import com.glmapper.ai.spi.ApiProviderRegistry;
import com.glmapper.ai.spi.ModelCatalog;
import com.glmapper.coding.http.api.config.DelphiCodingHttpAutoConfiguration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.util.List;

@AutoConfiguration
@Import(DelphiCodingHttpAutoConfiguration.class)
public class DelphiCodingAgentAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "defaultSpringAiProvider")
    @ConditionalOnBean(ChatModel.class)
    public ApiProvider defaultSpringAiProvider(ChatModel chatModel) {
        return new SpringAiChatModelProvider("spring-ai-openai", chatModel);
    }

    @Bean
    @ConditionalOnMissingBean
    public ApiProviderRegistry apiProviderRegistry(List<ApiProvider> providers) {
        ApiProviderRegistry registry = new ApiProviderRegistry();
        providers.forEach(registry::register);
        return registry;
    }

    @Bean
    @ConditionalOnMissingBean
    public ModelCatalog modelCatalog() {
        ModelCatalog catalog = new ModelCatalog();
        catalog.register(new Model(
                "gpt-4o-mini", "gpt-4o-mini",
                "spring-ai-openai", "openai",
                "https://api.openai.com/v1",
                true, List.of("text", "image"),
                new Model.CostModel(0, 0, 0, 0),
                128000, 16384
        ));
        return catalog;
    }

    @Bean
    @ConditionalOnMissingBean
    public AiRuntime aiRuntime(ApiProviderRegistry registry) {
        return new AiRuntime(registry);
    }
}
