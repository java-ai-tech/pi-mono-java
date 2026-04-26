package com.glmapper.coding.core.runtime;

@FunctionalInterface
public interface RunAbortController {
    void abort(String reason);
}

