package com.glmapper.coding.core.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;

@Document("subagent_states")
@CompoundIndex(name = "namespace_session_id_idx", def = "{'namespace':1,'sessionId':1,'id':1}", unique = true)
@CompoundIndex(name = "namespace_parent_status_idx", def = "{'namespace':1,'parentRunId':1,'status':1}")
@CompoundIndex(name = "namespace_session_status_idx", def = "{'namespace':1,'sessionId':1,'status':1}")
public class SubagentStateDocument {
    @Id
    private String id;
    private String parentRunId;
    private String tenantId;
    private String namespace;
    private String userId;
    private String projectKey;
    private String sessionId;
    private String role;
    private int depth;
    private String workspaceScope;
    private String task;
    private String contextText;
    private int maxDurationSeconds;
    private String ownerNodeId;
    private String status;
    private String summary;
    private String errorMessage;
    private Instant startedAt;
    private Instant endedAt;
    private Map<String, Object> details;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getParentRunId() { return parentRunId; }
    public void setParentRunId(String parentRunId) { this.parentRunId = parentRunId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public String getWorkspaceScope() { return workspaceScope; }
    public void setWorkspaceScope(String workspaceScope) { this.workspaceScope = workspaceScope; }
    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }
    public String getContextText() { return contextText; }
    public void setContextText(String contextText) { this.contextText = contextText; }
    public int getMaxDurationSeconds() { return maxDurationSeconds; }
    public void setMaxDurationSeconds(int maxDurationSeconds) { this.maxDurationSeconds = maxDurationSeconds; }
    public String getOwnerNodeId() { return ownerNodeId; }
    public void setOwnerNodeId(String ownerNodeId) { this.ownerNodeId = ownerNodeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public Map<String, Object> getDetails() { return details; }
    public void setDetails(Map<String, Object> details) { this.details = details; }
}
