package com.glmapper.agent.core;

import java.util.regex.Pattern;

/**
 * Validates namespace format for tenant isolation.
 * Rules: lowercase letter start, only lowercase letters/digits/hyphens, 3-64 chars.
 */
public final class NamespaceValidator {

    private static final Pattern NAMESPACE_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{2,63}$");

    private NamespaceValidator() {}

    /**
     * Validates the given namespace string.
     *
     * @param namespace the namespace to validate
     * @throws IllegalArgumentException if the namespace is null, blank, or does not match the required pattern
     */
    public static void validate(String namespace) {
        if (namespace == null || namespace.isBlank()) {
            throw new IllegalArgumentException("namespace is required");
        }
        // "default" and AgentConstants.DEFAULT_NAMESPACE are always valid
        if (AgentConstants.DEFAULT_NAMESPACE.equals(namespace)) {
            return;
        }
        if (!NAMESPACE_PATTERN.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "Invalid namespace '" + namespace + "': must start with a lowercase letter, "
                            + "contain only lowercase letters, digits, and hyphens, and be 3-64 characters long");
        }
    }
}
