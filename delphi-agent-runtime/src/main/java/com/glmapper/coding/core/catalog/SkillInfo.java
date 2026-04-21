package com.glmapper.coding.core.catalog;

public record SkillInfo(
        String name,
        String description,
        String path,
        String content,
        String entrypoint,
        String argsSchema,
        long timeoutMs
) {
    /** Backward-compatible constructor for skills without entrypoint. */
    public SkillInfo(String name, String description, String path, String content) {
        this(name, description, path, content, null, null, 0);
    }

    /** Backward-compatible constructor for skills without timeout. */
    public SkillInfo(String name, String description, String path, String content, String entrypoint, String argsSchema) {
        this(name, description, path, content, entrypoint, argsSchema, 0);
    }

    public boolean isExecutable() {
        return entrypoint != null && !entrypoint.isBlank();
    }

    /**
     * Get effective timeout in milliseconds. Returns custom timeout if set, otherwise default 30s.
     */
    public long getEffectiveTimeoutMs() {
        return timeoutMs > 0 ? timeoutMs : 30_000L;
    }
}
