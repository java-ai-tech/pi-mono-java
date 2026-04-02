package com.glmapper.ai.api;

import java.util.List;

public record Model(
        String id,
        String name,
        String api,
        String provider,
        String baseUrl,
        boolean reasoning,
        List<String> input,
        CostModel cost,
        int contextWindow,
        int maxTokens
) {
    public record CostModel(double input, double output, double cacheRead, double cacheWrite) {
    }
}
