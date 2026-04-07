package com.glmapper.ai.api;

import java.util.Map;

public record StreamOptions(
        Double temperature,
        Integer maxTokens,
        String apiKey,
        String transport,
        String cacheRetention,
        String sessionId,
        Integer maxRetryDelayMs,
        Map<String, String> headers,
        Map<String, Object> metadata,
        ThinkingLevel reasoning,
        Map<ThinkingLevel, Integer> thinkingBudgets
) {
}
