package com.glmapper.coding.core.mongo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDate;

@Document("usage_metrics")
@CompoundIndex(name = "namespace_date_idx", def = "{'namespace':1,'date':1}", unique = true)
public class UsageMetricsDocument {
    @Id
    private String id;
    private String namespace;
    private LocalDate date;
    private long totalInputTokens;
    private long totalOutputTokens;
    private long totalRequests;
    private long totalToolCalls;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public LocalDate getDate() { return date; }
    public void setDate(LocalDate date) { this.date = date; }

    public long getTotalInputTokens() { return totalInputTokens; }
    public void setTotalInputTokens(long totalInputTokens) { this.totalInputTokens = totalInputTokens; }

    public long getTotalOutputTokens() { return totalOutputTokens; }
    public void setTotalOutputTokens(long totalOutputTokens) { this.totalOutputTokens = totalOutputTokens; }

    public long getTotalRequests() { return totalRequests; }
    public void setTotalRequests(long totalRequests) { this.totalRequests = totalRequests; }

    public long getTotalToolCalls() { return totalToolCalls; }
    public void setTotalToolCalls(long totalToolCalls) { this.totalToolCalls = totalToolCalls; }
}
