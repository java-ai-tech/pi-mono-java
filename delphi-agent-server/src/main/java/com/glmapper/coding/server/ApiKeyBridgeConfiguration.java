package com.glmapper.coding.server;

import com.glmapper.coding.core.config.PiAgentProperties;
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

    private final Environment environment;
    private final PiAgentProperties properties;

    public ApiKeyBridgeConfiguration(Environment environment, PiAgentProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @PostConstruct
    void bridgeApiKeys() {
        List<String> keyNames = properties.apiKeys() != null ? properties.apiKeys() : List.of();
        for (String key : keyNames) {
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
