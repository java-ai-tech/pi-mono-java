package com.glmapper.coding.core.service;

import com.glmapper.agent.core.Agent;
import com.glmapper.coding.core.config.PiAgentProperties;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Manages agent session lifecycle with LRU eviction and idle reaping.
 */
@Component
public class SessionLifecycleManager {
    private static final int DEFAULT_MAX_SESSIONS = 2000;
    private static final Duration DEFAULT_IDLE_TTL = Duration.ofHours(24);
    private static final Duration DEFAULT_REAP_INTERVAL = Duration.ofMinutes(5);

    private final int maxSessions;
    private final Duration idleTtl;
    private final Map<String, ManagedSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "session-reaper");
        t.setDaemon(true);
        return t;
    });
    private Consumer<ManagedSession> onEvict;

    @Autowired
    public SessionLifecycleManager(PiAgentProperties properties) {
        this(
                resolveMaxSessions(properties),
                resolveIdleTtl(properties),
                resolveReapInterval(properties)
        );
    }

    public SessionLifecycleManager() {
        this(DEFAULT_MAX_SESSIONS, DEFAULT_IDLE_TTL, DEFAULT_REAP_INTERVAL);
    }

    public SessionLifecycleManager(int maxSessions, Duration idleTtl, Duration reapInterval) {
        this.maxSessions = maxSessions;
        this.idleTtl = idleTtl;
        scheduler.scheduleAtFixedRate(
                this::reapIdleSessions,
                reapInterval.toMillis(),
                reapInterval.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    private static int resolveMaxSessions(PiAgentProperties properties) {
        if (properties == null || properties.session() == null || properties.session().maxSessions() <= 0) {
            return DEFAULT_MAX_SESSIONS;
        }
        return properties.session().maxSessions();
    }

    private static Duration resolveIdleTtl(PiAgentProperties properties) {
        if (properties == null || properties.session() == null || properties.session().idleTtlHours() <= 0) {
            return DEFAULT_IDLE_TTL;
        }
        return Duration.ofHours(properties.session().idleTtlHours());
    }

    private static Duration resolveReapInterval(PiAgentProperties properties) {
        if (properties == null || properties.session() == null || properties.session().reapIntervalMinutes() <= 0) {
            return DEFAULT_REAP_INTERVAL;
        }
        return Duration.ofMinutes(properties.session().reapIntervalMinutes());
    }

    /**
     * Get or create a managed session.
     */
    public Agent getOrCreate(
            String sessionId,
            String namespace,
            Supplier<Agent> agentFactory,
            Supplier<AutoCloseable> subscriptionFactory
    ) {
        ManagedSession managed = sessions.computeIfAbsent(sessionId, key -> {
            // Check if we need to evict before creating
            if (sessions.size() >= maxSessions) {
                evictOldestIdle();
            }
            Agent agent = agentFactory.get();
            AutoCloseable subscription = subscriptionFactory.get();
            return new ManagedSession(sessionId, namespace, agent, subscription, Instant.now());
        });
        touch(sessionId);
        return managed.agent;
    }

    /**
     * Update last touched timestamp.
     */
    public void touch(String sessionId) {
        ManagedSession managed = sessions.get(sessionId);
        if (managed != null) {
            managed.lastTouchedAt.set(Instant.now());
        }
    }

    /**
     * Set active run ID for a session.
     */
    public void setActiveRun(String sessionId, String runId) {
        ManagedSession managed = sessions.get(sessionId);
        if (managed != null) {
            managed.activeRunId.set(runId);
        }
    }

    /**
     * Clear active run ID for a session.
     */
    public void clearActiveRun(String sessionId) {
        ManagedSession managed = sessions.get(sessionId);
        if (managed != null) {
            managed.activeRunId.set(null);
        }
    }

    /**
     * Remove idle sessions that have exceeded TTL.
     */
    public void reapIdleSessions() {
        Instant cutoff = Instant.now().minus(idleTtl);
        sessions.entrySet().removeIf(entry -> {
            ManagedSession managed = entry.getValue();
            boolean isIdle = managed.activeRunId.get() == null;
            boolean isExpired = managed.lastTouchedAt.get().isBefore(cutoff);
            if (isIdle && isExpired) {
                closeSession(managed);
                return true;
            }
            return false;
        });
    }

    /**
     * Evict the oldest idle session when capacity is reached.
     */
    public void evictOldestIdle() {
        ManagedSession oldest = null;
        Instant oldestTime = Instant.now();

        for (ManagedSession managed : sessions.values()) {
            if (managed.activeRunId.get() == null) {
                Instant lastTouched = managed.lastTouchedAt.get();
                if (lastTouched.isBefore(oldestTime)) {
                    oldestTime = lastTouched;
                    oldest = managed;
                }
            }
        }

        if (oldest != null) {
            if (onEvict != null) {
                onEvict.accept(oldest);
            }
            sessions.remove(oldest.sessionId);
            closeSession(oldest);
        }
    }

    /**
     * Remove a specific session.
     */
    public void remove(String sessionId) {
        ManagedSession managed = sessions.remove(sessionId);
        if (managed != null) {
            closeSession(managed);
        }
    }

    /**
     * Set eviction callback.
     */
    public void setOnEvict(Consumer<ManagedSession> onEvict) {
        this.onEvict = onEvict;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        sessions.values().forEach(this::closeSession);
        sessions.clear();
    }

    private void closeSession(ManagedSession managed) {
        try {
            if (managed.subscription != null) {
                managed.subscription.close();
            }
        } catch (Exception e) {
            // Log but don't propagate
        }
    }

    /**
     * Managed session holder.
     */
    public static class ManagedSession {
        public final String sessionId;
        public final String namespace;
        public final Agent agent;
        public final AutoCloseable subscription;
        public final Instant createdAt;
        public final AtomicReference<Instant> lastTouchedAt;
        public final AtomicReference<String> activeRunId;

        public ManagedSession(
                String sessionId,
                String namespace,
                Agent agent,
                AutoCloseable subscription,
                Instant createdAt
        ) {
            this.sessionId = sessionId;
            this.namespace = namespace;
            this.agent = agent;
            this.subscription = subscription;
            this.createdAt = createdAt;
            this.lastTouchedAt = new AtomicReference<>(createdAt);
            this.activeRunId = new AtomicReference<>();
        }
    }
}
