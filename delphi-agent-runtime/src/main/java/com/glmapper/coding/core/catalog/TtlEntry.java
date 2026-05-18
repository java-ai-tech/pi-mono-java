package com.glmapper.coding.core.catalog;

public final class TtlEntry<T> {
    private final T value;
    private final long expiresAtMillis;

    public TtlEntry(T value, long ttlMillis) {
        this.value = value;
        this.expiresAtMillis = ttlMillis <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() + ttlMillis;
    }

    public T value() {
        return value;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expiresAtMillis;
    }
}
