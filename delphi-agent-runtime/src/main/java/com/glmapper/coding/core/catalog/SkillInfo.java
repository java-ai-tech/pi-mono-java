package com.glmapper.coding.core.catalog;

public record SkillInfo(
        String name,
        String description,
        String path,
        String content,
        String entrypoint,
        String argsSchema
) {
    /** Backward-compatible constructor for skills without entrypoint. */
    public SkillInfo(String name, String description, String path, String content) {
        this(name, description, path, content, null, null);
    }

    public boolean isExecutable() {
        return entrypoint != null && !entrypoint.isBlank();
    }
}
