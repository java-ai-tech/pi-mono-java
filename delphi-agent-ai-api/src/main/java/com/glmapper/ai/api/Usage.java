package com.glmapper.ai.api;

public record Usage(
        long input,
        long output,
        long cacheRead,
        long cacheWrite,
        long totalTokens,
        Cost cost
) {
    public static Usage empty() {
        return new Usage(0, 0, 0, 0, 0, Cost.empty());
    }

    public record Cost(
            double input,
            double output,
            double cacheRead,
            double cacheWrite,
            double total
    ) {
        public static Cost empty() {
            return new Cost(0, 0, 0, 0, 0);
        }
    }
}
