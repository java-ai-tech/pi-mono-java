package com.glmapper.coding.core.tools.policy;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class ToolExecutionWrapper {

    private final ToolAuditWrapper toolAuditWrapper;

    public ToolExecutionWrapper(ToolAuditWrapper toolAuditWrapper) {
        this.toolAuditWrapper = toolAuditWrapper;
    }

    public AgentTool wrap(ToolRuntimeContext context, ToolInventory.Item item) {
        AgentTool delegate = item.tool();
        return new AgentTool() {
            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public String label() {
                return delegate.label();
            }

            @Override
            public String description() {
                return delegate.description();
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return delegate.parametersSchema();
            }

            @Override
            public CompletableFuture<AgentToolResult> execute(String toolCallId,
                                                              Map<String, Object> params,
                                                              AgentToolUpdateCallback onUpdate,
                                                              java.util.concurrent.CancellationException cancellation) {
                long startedAt = System.currentTimeMillis();
                return delegate.execute(toolCallId, params, onUpdate, cancellation)
                        .whenComplete((result, throwable) -> {
                            long duration = System.currentTimeMillis() - startedAt;
                            if (throwable != null) {
                                toolAuditWrapper.onExecution(
                                        context,
                                        item,
                                        "FAILED",
                                        throwable.getMessage(),
                                        duration
                                );
                            } else {
                                toolAuditWrapper.onExecution(
                                        context,
                                        item,
                                        "ALLOWED",
                                        null,
                                        duration
                                );
                            }
                        });
            }
        };
    }

    public AgentTool deny(ToolRuntimeContext context, ToolInventory.Item item, ToolPolicyDecision decision) {
        AgentTool delegate = item.tool();
        return new AgentTool() {
            @Override
            public String name() {
                return delegate.name();
            }

            @Override
            public String label() {
                return delegate.label();
            }

            @Override
            public String description() {
                String reason = decision.reason() == null ? "denied_by_policy" : decision.reason();
                return delegate.description() + " [denied by runtime policy: " + reason + "]";
            }

            @Override
            public Map<String, Object> parametersSchema() {
                return delegate.parametersSchema();
            }

            @Override
            public CompletableFuture<AgentToolResult> execute(String toolCallId,
                                                              Map<String, Object> params,
                                                              AgentToolUpdateCallback onUpdate,
                                                              java.util.concurrent.CancellationException cancellation) {
                String reason = decision.reason() == null ? "denied_by_policy" : decision.reason();
                toolAuditWrapper.onExecution(context, item, "DENIED", reason, 0);
                return CompletableFuture.completedFuture(new AgentToolResult(
                        List.of(new TextContent("Tool execution denied by runtime policy: " + reason, null)),
                        Map.of(
                                "error", true,
                                "policyDenied", true,
                                "reason", reason,
                                "toolName", delegate.name(),
                                "category", item.category().name()
                        )
                ));
            }
        };
    }
}
