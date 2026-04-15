package com.glmapper.coding.server;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;

@ConfigurationProperties(prefix = "pi")
public record PiAgentProperties(
    List<ModelConfig> models,
    DefaultsConfig defaults,
    SessionConfig session,
    CompactionConfig compaction,
    PlanningConfig planning,
    List<String> apiKeys
) {
    public record ModelConfig(
        String id, String name, String api, String provider, String baseUrl,
        boolean reasoning, List<String> input, int contextWindow, int maxTokens
    ) {}
    public record DefaultsConfig(String provider, String modelId) {}
    public record SessionConfig(int maxSessions, int idleTtlHours, int reapIntervalMinutes) {}
    public record CompactionConfig(double contextWindowRatio, int maxMessages, int defaultKeepCount) {}
    public record PlanningConfig(int timeoutSeconds) {}
}
