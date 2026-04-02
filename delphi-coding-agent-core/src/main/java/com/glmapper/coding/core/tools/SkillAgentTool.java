package com.glmapper.coding.core.tools;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;
import com.glmapper.ai.api.TextContent;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.execution.ExecutionOptions;
import com.glmapper.coding.core.execution.ExecutionResult;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Adapts a SkillInfo into an executable AgentTool.
 * If the skill has an entrypoint, it executes via ExecutionBackend (sandboxed).
 * Otherwise, it returns the skill content as instructions.
 */
public class SkillAgentTool implements AgentTool {
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
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "input", Map.of(
                                "type", "string",
                                "description", "Input or arguments for the skill execution"
                        )
                ),
                "required", List.of("input")
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
            String input = (String) params.getOrDefault("input", "");

            if (skill.isExecutable()) {
                return executeViaBackend(input);
            }
            return returnAsInstructions(input);
        });
    }

    private AgentToolResult executeViaBackend(String input) {
        // Copy skill directory contents into the workspace so both Local and Docker backends can find them
        Path skillDir = Paths.get(skill.path()).getParent();
        Path workspace = executionBackend.getWorkspacePath(executionContext.namespace(), executionContext.sessionId());
        try {
            Files.createDirectories(workspace);
            try (Stream<Path> files = Files.walk(skillDir)) {
                files.forEach(source -> {
                    Path target = workspace.resolve(skillDir.relativize(source));
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
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to copy skill files to workspace", e);
        }

        String command = skill.entrypoint() + " " + input;
        ExecutionResult result = executionBackend.execute(
                executionContext, command, ExecutionOptions.defaults()
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

    private AgentToolResult returnAsInstructions(String input) {
        String result = "[skill:" + skill.name() + "]\n" + skill.content() + "\n\n[user-input]\n" + input;
        return new AgentToolResult(
                List.of(new TextContent(result, null)),
                Map.of("skill", skill.name(), "executable", false)
        );
    }

    public SkillInfo getSkill() {
        return skill;
    }
}