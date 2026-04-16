package com.glmapper.coding.core.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document("audit_logs")
@CompoundIndex(name = "namespace_timestamp_idx", def = "{'namespace':1,'timestamp':-1}")
@CompoundIndex(name = "session_timestamp_idx", def = "{'sessionId':1,'timestamp':-1}")
public class AuditLogDocument {
    @Id
    private String id;
    private String namespace;
    private String userId;
    private String sessionId;
    private String action;
    private Instant timestamp;
    private Map<String, Object> details;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
