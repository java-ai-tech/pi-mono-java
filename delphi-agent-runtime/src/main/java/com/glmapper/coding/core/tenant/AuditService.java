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
     * 记录审计日志，包含业务命名空间、用户ID、会话ID、操作类型和额外详情信息
     *
     * @param namespace 业务命名空间，必填
     * @param userId    用户ID，选填
     * @param sessionId 会话ID，选填
     * @param action    操作类型，必填
     * @param details   额外详情信息，选填
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
