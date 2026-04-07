package com.glmapper.coding.core.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("agent_sessions")
@CompoundIndex(name = "project_updated_idx", def = "{'projectKey':1,'updatedAt':-1}")
@CompoundIndex(name = "namespace_project_updated_idx", def = "{'namespace':1,'projectKey':1,'updatedAt':-1}")
@CompoundIndex(name = "namespace_id_idx", def = "{'namespace':1,'id':1}")
public class SessionDocument {
    @Id
    private String id;
    private String namespace;
    private String ownerRef;
    private String projectKey;
    private String sessionName;
    private String modelProvider;
    private String modelId;
    private String thinkingLevel;
    private String systemPrompt;
    private String headEntryId;
    private int persistedMessageCount;
    private String steeringMode;
    private String followUpMode;
    private boolean autoCompactionEnabled;
    private boolean autoRetryEnabled;
    private Instant createdAt;
    private Instant updatedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getProjectKey() {
        return projectKey;
    }

    public void setProjectKey(String projectKey) {
        this.projectKey = projectKey;
    }

    public String getNamespace() {
        return namespace;
    }

    public void setNamespace(String namespace) {
        this.namespace = namespace;
    }

    public String getOwnerRef() {
        return ownerRef;
    }

    public void setOwnerRef(String ownerRef) {
        this.ownerRef = ownerRef;
    }

    public String getSessionName() {
        return sessionName;
    }

    public void setSessionName(String sessionName) {
        this.sessionName = sessionName;
    }

    public String getModelProvider() {
        return modelProvider;
    }

    public void setModelProvider(String modelProvider) {
        this.modelProvider = modelProvider;
    }

    public String getModelId() {
        return modelId;
    }

    public void setModelId(String modelId) {
        this.modelId = modelId;
    }

    public String getThinkingLevel() {
        return thinkingLevel;
    }

    public void setThinkingLevel(String thinkingLevel) {
        this.thinkingLevel = thinkingLevel;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public String getHeadEntryId() {
        return headEntryId;
    }

    public void setHeadEntryId(String headEntryId) {
        this.headEntryId = headEntryId;
    }

    public int getPersistedMessageCount() {
        return persistedMessageCount;
    }

    public void setPersistedMessageCount(int persistedMessageCount) {
        this.persistedMessageCount = persistedMessageCount;
    }

    public String getSteeringMode() {
        return steeringMode;
    }

    public void setSteeringMode(String steeringMode) {
        this.steeringMode = steeringMode;
    }

    public String getFollowUpMode() {
        return followUpMode;
    }

    public void setFollowUpMode(String followUpMode) {
        this.followUpMode = followUpMode;
    }

    public boolean isAutoCompactionEnabled() {
        return autoCompactionEnabled;
    }

    public void setAutoCompactionEnabled(boolean autoCompactionEnabled) {
        this.autoCompactionEnabled = autoCompactionEnabled;
    }

    public boolean isAutoRetryEnabled() {
        return autoRetryEnabled;
    }

    public void setAutoRetryEnabled(boolean autoRetryEnabled) {
        this.autoRetryEnabled = autoRetryEnabled;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
