package com.glmapper.coding.server;

import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * Bridges API key properties loaded by Spring (e.g. from .env file via
 * spring.config.import) into System properties so that Provider classes
 * can resolve them via System.getProperty() when System.getenv() is empty.
 */
@Configuration
public class ApiKeyBridgeConfiguration {

    private static final List<String> API_KEY_NAMES = List.of(
            "OPENAI_API_KEY",
            "DEEPSEEK_API_KEY",
            "ZHIPUAI_API_KEY"
    );

    private final Environment environment;

    public ApiKeyBridgeConfiguration(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void bridgeApiKeys() {
        for (String key : API_KEY_NAMES) {
            if (System.getenv(key) != null || System.getProperty(key) != null) {
                continue;
            }
            String value = environment.getProperty(key);
            if (value != null && !value.isBlank()) {
                System.setProperty(key, value);
            }
        }
    }
}
