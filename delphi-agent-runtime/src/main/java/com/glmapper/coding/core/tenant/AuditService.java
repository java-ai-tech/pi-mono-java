package com.glmapper.coding.core.tenant;

import com.glmapper.coding.core.mongo.AuditLogDocument;
import com.glmapper.coding.core.mongo.AuditLogRepository;
import com.glmapper.coding.core.config.PiAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronously records audit log entries to MongoDB.
 */
@Component
public class AuditService {
    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository auditLogRepository;
    private final PiAgentProperties properties;

    public AuditService(AuditLogRepository auditLogRepository, PiAgentProperties properties) {
        this.auditLogRepository = auditLogRepository;
        this.properties = properties;
    }

    /**
     * Records an audit event asynchronously.
     */
    public void record(String namespace, String userId, String sessionId,
                        String action, Map<String, Object> details) {
        if (properties.audit() == null || !properties.audit().enabled()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                AuditLogDocument doc = new AuditLogDocument();
                doc.setNamespace(namespace);
                doc.setUserId(userId);
                doc.setSessionId(sessionId);
                doc.setAction(action);
                doc.setTimestamp(Instant.now());
                doc.setDetails(details);
                auditLogRepository.save(doc);
            } catch (Exception e) {
                log.warn("Failed to write audit log: namespace={} action={}", namespace, action, e);
            }
        });
    }
}
