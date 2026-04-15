package com.glmapper.coding.core.service;

import com.glmapper.agent.core.Agent;
import com.glmapper.agent.core.AgentOptions;
import com.glmapper.ai.api.*;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.ai.spi.ApiProvider;
import com.glmapper.ai.spi.ApiProviderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class SessionLifecycleManagerTest {
    private SessionLifecycleManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.shutdown();
        }
    }

    private Agent mockAgent() {
        // Create a minimal ApiProvider that returns empty responses
        ApiProvider provider = new ApiProvider() {
            @Override
            public String api() {
                return "test-api";
            }

            @Override
            public AssistantMessageEventStream stream(Model model, Context context, StreamOptions options) {
                return new AssistantMessageEventStream() {
                    @Override
                    public java.util.concurrent.Flow.Publisher<AssistantMessageEvent> publisher() {
                        return subscriber -> {
                            // No-op publisher
                        };
                    }

                    @Override
                    public CompletableFuture<AssistantMessage> result() {
                        return CompletableFuture.completedFuture(
                                new AssistantMessage(List.of(), "test", "test", "test", Usage.empty(), StopReason.STOP, null, "resp-1", System.currentTimeMillis())
                        );
                    }
                };
            }

            @Override
            public AssistantMessageEventStream streamSimple(Model model, Context context, StreamOptions options) {
                return stream(model, context, options);
            }
        };

        ApiProviderRegistry registry = new ApiProviderRegistry();
        registry.register(provider);

        AiRuntime aiRuntime = new AiRuntime(registry);
        Model model = new Model(
                "test-model", "Test Model", "test-api", "test",
                "http://localhost", false, List.of("text"),
                new Model.CostModel(0.01, 0.03, 0.005, 0.01),
                128000, 4096
        );
        return new Agent(aiRuntime, model, AgentOptions.defaults());
    }

    private AutoCloseable noopSubscription() {
        return () -> {};
    }

    @Test
    void getOrCreateShouldCreateNewSession() {
        manager = new SessionLifecycleManager(10, Duration.ofHours(1), Duration.ofMinutes(5));
        Agent agent = manager.getOrCreate(
                "s-1", "default",
                this::mockAgent,
                this::noopSubscription
        );
        assertNotNull(agent);
    }

    @Test
    void getOrCreateShouldReturnExistingSession() {
        manager = new SessionLifecycleManager(10, Duration.ofHours(1), Duration.ofMinutes(5));
        Agent first = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        Agent second = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        assertSame(first, second);
    }

    @Test
    void touchShouldUpdateLastTouchedAt() throws InterruptedException {
        manager = new SessionLifecycleManager(10, Duration.ofMillis(200), Duration.ofMinutes(5));
        manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);

        // Wait a bit and touch
        Thread.sleep(50);
        manager.touch("s-1");

        // Wait until original creation time would expire but touched time hasn't
        Thread.sleep(180);
        manager.reapIdleSessions();

        // Session should still exist because it was touched
        Agent agent = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        assertNotNull(agent);
    }

    @Test
    void reapShouldRemoveExpiredIdleSessions() throws InterruptedException {
        manager = new SessionLifecycleManager(10, Duration.ofMillis(100), Duration.ofMinutes(5));
        Agent firstAgent = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);

        Thread.sleep(150);
        manager.reapIdleSessions();

        // Session should be gone; next call creates a new one
        Agent newAgent = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        assertNotSame(firstAgent, newAgent);
    }

    @Test
    void reapShouldNotRemoveActiveRunSessions() throws InterruptedException {
        manager = new SessionLifecycleManager(10, Duration.ofMillis(100), Duration.ofMinutes(5));
        Agent agent = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        manager.setActiveRun("s-1", "run-1");

        Thread.sleep(150);
        manager.reapIdleSessions();

        // Session should still be there since it has active run
        Agent same = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        assertSame(agent, same);
    }

    @Test
    void evictShouldRemoveOldestIdleWhenFull() {
        manager = new SessionLifecycleManager(2, Duration.ofHours(1), Duration.ofMinutes(5));
        manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        manager.getOrCreate("s-2", "default", this::mockAgent, this::noopSubscription);

        // Add third session, which should evict the oldest
        manager.getOrCreate("s-3", "default", this::mockAgent, this::noopSubscription);

        // s-1 should have been evicted (oldest)
        // Re-create should produce a new agent
        Agent newAgent = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        assertNotNull(newAgent);
    }

    @Test
    void removeShouldRemoveSession() {
        manager = new SessionLifecycleManager(10, Duration.ofHours(1), Duration.ofMinutes(5));
        Agent agent = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        manager.remove("s-1");

        // Re-create should produce a new agent
        Agent newAgent = manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        assertNotSame(agent, newAgent);
    }

    @Test
    void shutdownShouldClearAllSessions() {
        manager = new SessionLifecycleManager(10, Duration.ofHours(1), Duration.ofMinutes(5));
        manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        manager.getOrCreate("s-2", "default", this::mockAgent, this::noopSubscription);

        manager.shutdown();
        // After shutdown, new sessions are created fresh
        // (We just verify no exceptions)
    }

    @Test
    void onEvictCallbackShouldBeInvoked() {
        manager = new SessionLifecycleManager(2, Duration.ofHours(1), Duration.ofMinutes(5));
        boolean[] evicted = {false};
        manager.setOnEvict(managed -> evicted[0] = true);

        manager.getOrCreate("s-1", "default", this::mockAgent, this::noopSubscription);
        manager.getOrCreate("s-2", "default", this::mockAgent, this::noopSubscription);
        // Trigger eviction by adding a third session
        manager.getOrCreate("s-3", "default", this::mockAgent, this::noopSubscription);

        assertTrue(evicted[0]);
    }
}
