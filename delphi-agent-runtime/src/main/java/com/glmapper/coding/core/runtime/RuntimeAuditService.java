package com.glmapper.coding.core.runtime;

import com.glmapper.coding.core.tenant.AuditService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RuntimeAuditService {

    @Autowired(required = false)
    private AuditService auditService;

    public void recordRunStarted(AgentRunContext context) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", context.runId());
        details.put("tenantId", context.tenantId());
        details.put("projectKey", context.projectKey());
        details.put("queueMode", context.queueMode() == null ? null : context.queueMode().name());
        details.put("startedAt", context.startedAt() == null ? Instant.now().toString() : context.startedAt().toString());
        record(context, "run_started", details);
    }

    public void recordRunCompleted(AgentRunContext context, String finalText) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", context.runId());
        details.put("finalTextLength", finalText == null ? 0 : finalText.length());
        details.put("completedAt", Instant.now().toString());
        record(context, "run_completed", details);
    }

    public void recordRunFailed(AgentRunContext context, RunFailureType failureType, String message) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", context.runId());
        details.put("failureType", failureType == null ? RunFailureType.UNKNOWN.name() : failureType.name());
        details.put("message", sanitizeText(message, 300));
        details.put("failedAt", Instant.now().toString());
        record(context, "run_failed", details);
    }

    public void recordQueueDecision(AgentRunContext context, RunQueueDecision decision) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", context.runId());
        details.put("decision", decision == null || decision.type() == null ? null : decision.type().name());
        details.put("reason", decision == null ? null : decision.reason());
        record(context, "queue_decision", details);
    }

    public void recordPolicyDecision(ToolRuntimeAuditRecord record) {
        if (auditService == null || record == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", record.runId());
        details.put("subagentId", record.subagentId());
        details.put("toolName", record.toolName());
        details.put("category", record.category());
        details.put("decision", record.decision());
        details.put("reason", record.reason());
        details.put("durationMs", record.durationMs());
        details.put("resource", sanitizeText(record.resource(), 200));
        details.put("timestamp", Instant.now().toString());
        auditService.record(record.namespace(), record.userId(), record.sessionId(), "tool_policy", details);
    }

    public void recordToolExecution(ToolRuntimeAuditRecord record) {
        if (auditService == null || record == null) {
            return;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("runId", record.runId());
        details.put("subagentId", record.subagentId());
        details.put("toolName", record.toolName());
        details.put("category", record.category());
        details.put("decision", record.decision());
        details.put("reason", record.reason());
        details.put("durationMs", record.durationMs());
        details.put("resource", sanitizeText(record.resource(), 200));
        details.put("timestamp", Instant.now().toString());
        auditService.record(record.namespace(), record.userId(), record.sessionId(), "tool_execution", details);
    }

    public void record(AgentRunContext context, String action, Map<String, Object> details) {
        if (auditService == null || context == null) {
            return;
        }
        Map<String, Object> sanitized = new LinkedHashMap<>(details == null ? Map.of() : details);
        if (context.tenantId() != null && !context.tenantId().isBlank()) {
            sanitized.putIfAbsent("tenantId", context.tenantId());
        }
        if (context.projectKey() != null && !context.projectKey().isBlank()) {
            sanitized.putIfAbsent("projectKey", context.projectKey());
        }
        auditService.record(context.namespace(), context.userId(), context.sessionId(), action, sanitized);
    }

    private String sanitizeText(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String normalized = value
                .replaceAll("(?i)api[-_ ]?key\\s*[:=]\\s*[^\\s,;]+", "api_key=[REDACTED]")
                .replaceAll("(?i)token\\s*[:=]\\s*[^\\s,;]+", "token=[REDACTED]");
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen) + "...";
    }

    public record ToolRuntimeAuditRecord(
            String namespace,
            String userId,
            String sessionId,
            String runId,
            String subagentId,
            String toolName,
            String category,
            String decision,
            String reason,
            long durationMs,
            String resource
    ) {
    }
}

