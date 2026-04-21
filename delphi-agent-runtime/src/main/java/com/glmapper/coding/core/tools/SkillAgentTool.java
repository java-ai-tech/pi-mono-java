package com.glmapper.coding.core.tools;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.execution.ExecutionOptions;
import com.glmapper.coding.core.execution.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Adapts a SkillInfo into an executable AgentTool.
 * If the skill has an entrypoint, it executes via ExecutionBackend (sandboxed).
 * Otherwise, it returns the skill content as instructions.
 */
public class SkillAgentTool implements AgentTool {
    private static final Logger log = LoggerFactory.getLogger(SkillAgentTool.class);
    private static final String INPUT_PARAM = "input";
    private static final String SKILL_ARGS_JSON_ENV = "PI_SKILL_ARGS_JSON";
    private static final String SKILL_WORKSPACE_DIR = ".skills";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Only allow relative paths with safe characters, no path traversal. */
    private static final Pattern SAFE_ENTRYPOINT = Pattern.compile("^\\./[a-zA-Z0-9._/-]+$");

    /** Track which skill directories have been copied per workspace to avoid redundant copies. */
    private static final Set<String> copiedSkillDirs = ConcurrentHashMap.newKeySet();

    private final SkillInfo skill;
    private final ExecutionBackend executionBackend;
    private final ExecutionContext executionContext;

    public SkillAgentTool(SkillInfo skill, ExecutionBackend executionBackend, ExecutionContext executionContext) {
        this.skill = skill;
        this.executionBackend = executionBackend;
        this.executionContext = executionContext;
    }

    @Override
    public String name() {
        return "skill_" + skill.name();
    }

    @Override
    public String label() {
        return skill.description();
    }

    @Override
    public String description() {
        if (skill.isExecutable()) {
            return "Execute skill script: " + skill.name() + ". " + skill.description();
        }
        return "Load skill instructions: " + skill.name() + ". " + skill.description();
    }

    @Override
    public Map<String, Object> parametersSchema() {
        Map<String, Object> mergedProperties = new LinkedHashMap<>(readSchemaProperties(skill.argsSchema()));
        mergedProperties.putIfAbsent(INPUT_PARAM, Map.of(
                "type", "string",
                "description", "Raw input string for the skill. Recommended when the skill expects command-line style args."
        ));

        return Map.of(
                "type", "object",
                "properties", mergedProperties
        );
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(
            String toolCallId,
            Map<String, Object> params,
            AgentToolUpdateCallback onUpdate,
            CancellationException cancellation
    ) {
        return CompletableFuture.supplyAsync(() -> {
            Map<String, Object> safeParams = params == null ? Map.of() : params;

            // Only validate params for executable skills (scripts need correct args)
            // Instructional skills just inject content into context, no strict validation needed
            if (skill.isExecutable() && skill.argsSchema() != null && !skill.argsSchema().isBlank()) {
                validateParams(safeParams);
            }

            String input = extractInput(safeParams);

            if (skill.isExecutable()) {
                return executeViaBackend(input, safeParams);
            }
            return returnAsInstructions(input, safeParams);
        });
    }

    private void validateParams(Map<String, Object> params) {
        try {
            Map<String, Object> schema = OBJECT_MAPPER.readValue(skill.argsSchema(), Map.class);
            Object properties = schema.get("properties");
            if (!(properties instanceof Map<?, ?> propsMap)) {
                return;
            }

            for (Map.Entry<?, ?> entry : propsMap.entrySet()) {
                String propName = String.valueOf(entry.getKey());
                if (!(entry.getValue() instanceof Map<?, ?> propSchema)) {
                    continue;
                }

                Object required = schema.get("required");
                boolean isRequired = required instanceof List<?> list && list.contains(propName);

                if (isRequired && !params.containsKey(propName)) {
                    throw new IllegalArgumentException("Missing required parameter: " + propName);
                }

                if (params.containsKey(propName)) {
                    Object value = params.get(propName);
                    String expectedType = String.valueOf(propSchema.get("type"));
                    validateType(propName, value, expectedType);
                }
            }
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse argsSchema for skill {}: {}", skill.name(), e.getMessage());
        }
    }

    private void validateType(String paramName, Object value, String expectedType) {
        if (value == null) {
            return;
        }

        boolean valid = switch (expectedType) {
            case "string" -> value instanceof String;
            case "number", "integer" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "array" -> value instanceof List;
            case "object" -> value instanceof Map;
            default -> true;
        };

        if (!valid) {
            throw new IllegalArgumentException(
                String.format("Parameter '%s' has invalid type. Expected: %s, got: %s",
                    paramName, expectedType, value.getClass().getSimpleName())
            );
        }
    }

    private AgentToolResult executeViaBackend(String input, Map<String, Object> params) {
        // Validate entrypoint for security
        if (!SAFE_ENTRYPOINT.matcher(skill.entrypoint()).matches()) {
            throw new IllegalArgumentException(
                "Unsafe entrypoint: " + skill.entrypoint() +
                ". Only relative paths starting with './' and containing safe characters are allowed."
            );
        }

        Path skillDir = Paths.get(skill.path()).getParent();
        Path workspace = executionBackend.getWorkspacePath(executionContext.namespace(), executionContext.sessionId());
        Path skillWorkspace = workspace.resolve(SKILL_WORKSPACE_DIR).resolve(safeWorkspaceDirName(skill.name()));

        // Optimize: only copy if not already copied in this workspace
        String copyKey = workspace.toString() + ":" + skill.path();
        if (!copiedSkillDirs.contains(copyKey)) {
            try {
                Files.createDirectories(skillWorkspace);
                try (Stream<Path> files = Files.walk(skillDir)) {
                    files.forEach(source -> {
                        Path target = skillWorkspace.resolve(skillDir.relativize(source));
                        try {
                            if (Files.isDirectory(source)) {
                                Files.createDirectories(target);
                            } else {
                                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                            }
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
                }
                copiedSkillDirs.add(copyKey);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to copy skill files to workspace", e);
            }
        }

        Map<String, Object> structuredArgs = extractStructuredArgs(params);
        String resolvedInput = resolveExecutableInput(input, structuredArgs);
        String relativeSkillWorkspace = workspace.relativize(skillWorkspace).toString().replace("\\", "/");
        String command = "cd " + shellQuote(relativeSkillWorkspace) + " && " + shellQuote(skill.entrypoint())
                + (resolvedInput.isBlank() ? "" : " " + resolvedInput);

        ExecutionOptions defaults = ExecutionOptions.defaults();
        Map<String, String> envVars = new LinkedHashMap<>(defaults.envVars());
        if (!structuredArgs.isEmpty()) {
            envVars.put(SKILL_ARGS_JSON_ENV, toJson(structuredArgs));
        }

        // Use skill-specific timeout if configured
        long timeoutMs = skill.getEffectiveTimeoutMs();
        ExecutionResult result = executionBackend.execute(
                executionContext,
                command,
                new ExecutionOptions(timeoutMs, defaults.maxOutputBytes(), envVars)
        );

        String output;
        if (result.isSuccess()) {
            output = result.stdout();
        } else {
            output = "[skill:" + skill.name() + " exitCode=" + result.exitCode() + "]\n"
                    + result.stdout() + "\n" + result.stderr();
        }

        return new AgentToolResult(
                List.of(new TextContent(output, null)),
                Map.of("skill", skill.name(), "exitCode", result.exitCode(), "executable", true)
        );
    }

    private AgentToolResult returnAsInstructions(String input, Map<String, Object> params) {
        String effectiveInput = input;
        if (effectiveInput.isBlank()) {
            Map<String, Object> structuredArgs = extractStructuredArgs(params);
            if (!structuredArgs.isEmpty()) {
                effectiveInput = toJson(structuredArgs);
            }
        }
        String result = "[skill:" + skill.name() + "]\n" + skill.content() + "\n\n[user-input]\n" + effectiveInput;
        return new AgentToolResult(
                List.of(new TextContent(result, null)),
                Map.of("skill", skill.name(), "executable", false)
        );
    }

    private String extractInput(Map<String, Object> params) {
        Object inputValue = params.get(INPUT_PARAM);
        if (inputValue == null) {
            return "";
        }
        return String.valueOf(inputValue).trim();
    }

    private Map<String, Object> extractStructuredArgs(Map<String, Object> params) {
        Map<String, Object> args = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (INPUT_PARAM.equals(entry.getKey()) || entry.getValue() == null) {
                continue;
            }
            args.put(entry.getKey(), entry.getValue());
        }
        return args;
    }

    private String resolveExecutableInput(String input, Map<String, Object> structuredArgs) {
        if (input != null && !input.isBlank()) {
            return input;
        }
        if (structuredArgs.isEmpty()) {
            return "";
        }
        if (structuredArgs.size() == 1) {
            Object singleValue = structuredArgs.values().iterator().next();
            if (singleValue instanceof String || singleValue instanceof Number || singleValue instanceof Boolean) {
                return shellQuote(String.valueOf(singleValue));
            }
        }
        return shellQuote(toJson(structuredArgs));
    }

    private String toJson(Map<String, Object> value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize skill arguments", e);
        }
    }

    private Map<String, Object> readSchemaProperties(String schemaText) {
        if (schemaText == null || schemaText.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = OBJECT_MAPPER.readValue(schemaText, Object.class);
            if (!(parsed instanceof Map<?, ?> rawSchema)) {
                return Map.of();
            }
            Object rawProperties = rawSchema.get("properties");
            if (!(rawProperties instanceof Map<?, ?> propertiesMap)) {
                return Map.of();
            }
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : propertiesMap.entrySet()) {
                if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?>)) {
                    continue;
                }
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    private String safeWorkspaceDirName(String rawName) {
        String normalized = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]+", "-")
                .replaceAll("^-+", "")
                .replaceAll("-+$", "");
        return normalized.isBlank() ? "skill" : normalized;
    }

    private String shellQuote(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\"'\"'") + "'";
    }

    public SkillInfo getSkill() {
        return skill;
    }
}
