package com.glmapper.coding.server;

import com.glmapper.ai.api.Model;
import com.glmapper.ai.provider.springai.SpringAiChatModelProvider;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.ai.spi.ApiProvider;
import com.glmapper.ai.spi.ApiProviderRegistry;
import com.glmapper.ai.spi.ModelCatalog;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class AiProviderConfiguration {

    @Bean
    public OpenAiChatModel deepseekChatModel() {
        String apiKey = resolveKey("DEEPSEEK_API_KEY");
        var api = OpenAiApi.builder()
                .baseUrl("https://api.deepseek.com")
                .apiKey(apiKey)
                .build();
        return OpenAiChatModel.builder().openAiApi(api).build();
    }

    @Bean
    public ApiProvider deepseekProvider(@Qualifier("deepseekChatModel") OpenAiChatModel deepseekChatModel) {
        return new SpringAiChatModelProvider("spring-ai-deepseek", deepseekChatModel);
    }

    @Bean
    public ApiProvider zhipuaiProvider(ZhiPuAiChatModel zhipuaiChatModel) {
        return new SpringAiChatModelProvider("spring-ai-zhipuai", zhipuaiChatModel);
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
