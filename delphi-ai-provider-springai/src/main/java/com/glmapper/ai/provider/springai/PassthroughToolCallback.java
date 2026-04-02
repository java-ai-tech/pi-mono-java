package com.glmapper.ai.provider.springai;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

/**
 * A passthrough ToolCallback that declares tool schemas to the LLM
 * but never executes them. Tool execution is handled by the Agent loop.
 */
final class PassthroughToolCallback implements ToolCallback {
    private final ToolDefinition toolDefinition;

    PassthroughToolCallback(String name, String description, String inputSchema) {
        this.toolDefinition = ToolDefinition.builder()
                .name(name)
                .description(description)
                .inputSchema(inputSchema)
                .build();
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return toolDefinition;
    }

    @Override
    public String call(String toolInput) {
        // Never called — Agent loop handles tool execution
        throw new UnsupportedOperationException(
                "Tool '" + toolDefinition.name() + "' should be executed by Agent, not Spring AI");
    }
}
