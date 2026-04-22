package com.glmapper.coding.core.tools.builtin;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.coding.core.config.BuiltinToolsProperties;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class BuiltinToolFactory {
    private static final List<String> FALLBACK_AVAILABLE = List.of("read", "bash", "edit", "write", "grep", "find", "ls");
    private static final List<String> FALLBACK_DEFAULT_ENABLED = List.of("read", "bash", "edit", "write");

    private final ExecutionBackend executionBackend;
    private final BuiltinToolsProperties properties;

    public BuiltinToolFactory(ExecutionBackend executionBackend, BuiltinToolsProperties properties) {
        this.executionBackend = executionBackend;
        this.properties = properties;
    }

    public List<AgentTool> createDefaultTools(ExecutionContext context) {
        if (properties == null || !properties.isEnabled()) {
            return List.of();
        }
        List<String> available = normalizedUnique(properties.getAvailable(), FALLBACK_AVAILABLE);
        List<String> enabled = normalizedUnique(properties.getDefaultEnabled(), FALLBACK_DEFAULT_ENABLED);

        Set<String> availableSet = new HashSet<>(available);
        List<AgentTool> tools = new ArrayList<>();
        for (String name : enabled) {
            if (!availableSet.contains(name)) {
                continue;
            }
            AgentTool tool = createTool(name, context);
            if (tool != null) {
                tools.add(tool);
            }
        }
        return tools;
    }

    public Optional<AgentTool> resolveTool(String toolName, ExecutionContext context) {
        String normalized = normalizeToolName(toolName);
        if (normalized == null) {
            return Optional.empty();
        }

        List<String> available = normalizedUnique(properties.getAvailable(), FALLBACK_AVAILABLE);
        if (!available.contains(normalized)) {
            return Optional.empty();
        }

        AgentTool tool = createTool(normalized, context);
        return Optional.ofNullable(tool);
    }

    private AgentTool createTool(String name, ExecutionContext context) {
        return switch (name) {
            case "read" -> new ReadBuiltinTool(
                    executionBackend,
                    context,
                    properties.getRead().getMaxLines(),
                    properties.getRead().getMaxBytes()
            );
            case "write" -> new WriteBuiltinTool(executionBackend, context);
            case "edit" -> new EditBuiltinTool(executionBackend, context);
            case "bash" -> new BashBuiltinTool(
                    executionBackend,
                    context,
                    properties.getBash().getDefaultTimeoutSeconds(),
                    properties.getBash().getMaxLines(),
                    properties.getBash().getMaxBytes()
            );
            case "grep" -> new GrepBuiltinTool(
                    executionBackend,
                    context,
                    properties.getGrep().getDefaultLimit(),
                    properties.getGrep().getMaxLineLength(),
                    properties.getGrep().getMaxBytes()
            );
            case "find" -> new FindBuiltinTool(
                    executionBackend,
                    context,
                    properties.getFind().getDefaultLimit(),
                    properties.getFind().getMaxBytes()
            );
            case "ls" -> new LsBuiltinTool(
                    executionBackend,
                    context,
                    properties.getLs().getDefaultLimit(),
                    properties.getLs().getMaxBytes()
            );
            default -> null;
        };
    }

    private List<String> normalizedUnique(List<String> input, List<String> fallback) {
        List<String> source = (input == null || input.isEmpty()) ? fallback : input;
        List<String> output = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String item : source) {
            String normalized = normalizeToolName(item);
            if (normalized == null || !seen.add(normalized)) {
                continue;
            }
            output.add(normalized);
        }
        return output;
    }

    private String normalizeToolName(String toolName) {
        if (toolName == null) {
            return null;
        }
        String normalized = toolName.trim().toLowerCase(Locale.ROOT);
        return normalized.isEmpty() ? null : normalized;
    }
}
