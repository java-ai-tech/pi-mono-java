package com.glmapper.coding.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "pi")
public record PiAgentProperties(
    List<ModelConfig> models,
    DefaultsConfig defaults,
    SessionConfig session,
    CompactionConfig compaction,
    PlanningConfig planning,
    List<String> apiKeys,
    RateLimitConfig rateLimit,
    QuotaConfig quota,
    AuditConfig audit,
    MeteringConfig metering
) {
    public record ModelConfig(
        String id, String name, String api, String provider, String baseUrl,
        boolean reasoning, List<String> input, int contextWindow, int maxTokens
    ) {}
    public record DefaultsConfig(String provider, String modelId) {}
    public record SessionConfig(int maxSessions, int idleTtlHours, int reapIntervalMinutes) {}
    public record CompactionConfig(double contextWindowRatio, int maxMessages, int defaultKeepCount) {}
    public record PlanningConfig(int timeoutSeconds) {}
    public record RateLimitConfig(boolean enabled, int defaultRpm) {}
    public record QuotaConfig(boolean enabled, QuotaDefaults defaults, Map<String, QuotaDefaults> overrides) {}
    public record QuotaDefaults(int maxConcurrentSessions, long dailyTokenLimit, long cpuQuota, String memoryLimit, int pidsLimit) {}
    public record AuditConfig(boolean enabled) {}
    public record MeteringConfig(boolean enabled, int flushIntervalSeconds) {}
}
