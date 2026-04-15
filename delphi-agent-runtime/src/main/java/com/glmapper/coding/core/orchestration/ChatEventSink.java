package com.glmapper.coding.core.orchestration;

import java.util.Map;

@FunctionalInterface
public interface ChatEventSink {
    void emit(String eventName, Map<String, Object> data);
}
