package com.glmapper.coding.sdk;

import com.glmapper.coding.core.domain.SessionStateSnapshot;
import com.glmapper.coding.core.domain.SessionStats;

import java.util.concurrent.CompletableFuture;

public interface AgentSessionHandle {
    String sessionId();

    CompletableFuture<Void> prompt(String message);

    CompletableFuture<Void> cont();

    void steer(String message);

    void followUp(String message);

    void abort();

    void compact(Integer keepRecentMessages);

    void setSteeringMode(String mode);

    void setFollowUpMode(String mode);

    void setAutoCompaction(boolean enabled);

    void setAutoRetry(boolean enabled);

    SessionStats stats();

    String lastAssistantText();

    SessionStateSnapshot state();
}
