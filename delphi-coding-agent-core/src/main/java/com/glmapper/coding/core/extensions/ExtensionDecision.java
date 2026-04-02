package com.glmapper.coding.core.extensions;

public record ExtensionDecision(
        boolean cancel,
        String reason
) {
    public static ExtensionDecision proceed() {
        return new ExtensionDecision(false, null);
    }

    public static ExtensionDecision cancel(String reason) {
        return new ExtensionDecision(true, reason == null ? "Cancelled by extension" : reason);
    }
}
