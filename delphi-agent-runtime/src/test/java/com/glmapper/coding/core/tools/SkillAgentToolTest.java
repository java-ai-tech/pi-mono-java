package com.glmapper.coding.core.tools;

import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.execution.ExecutionOptions;
import com.glmapper.coding.core.execution.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class SkillAgentToolTest {

    @TempDir
    Path tempDir;

    @Test
    void executableSkillShouldRunViaBackend() throws Exception {
        // Create a real skill directory with SKILL.md and deploy.sh
        Path skillDir = tempDir.resolve("skills/deploy");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nentrypoint: \"./deploy.sh\"\n---\n# Deploy");
        Files.writeString(skillDir.resolve("deploy.sh"), "#!/bin/bash\necho deployed");

        // Create a workspace directory for the backend to return
        Path workspace = tempDir.resolve("workspaces/tenant-a/s-1");
        Files.createDirectories(workspace);

        ExecutionBackend backend = Mockito.mock(ExecutionBackend.class);
        Mockito.when(backend.getWorkspacePath("tenant-a", "s-1")).thenReturn(workspace);
        Mockito.when(backend.execute(any(ExecutionContext.class), anyString(), any(ExecutionOptions.class)))
                .thenReturn(new ExecutionResult(0, "script output", "", 10L, false, false));

        SkillInfo skill = new SkillInfo("deploy", "Deploy to staging",
                skillDir.resolve("SKILL.md").toString(),
                "# Deploy\nDeploy script", "./deploy.sh", null);
        ExecutionContext ctx = new ExecutionContext("tenant-a", "s-1", null);
        SkillAgentTool tool = new SkillAgentTool(skill, backend, ctx);

        AgentToolResult result = tool.execute("tc-1", Map.of("input", "--env staging"), null, null).get();

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.details();
        assertEquals(true, details.get("executable"));
        assertEquals(0, details.get("exitCode"));
        // Skill files should be copied to skill-isolated workspace
        assertTrue(Files.exists(workspace.resolve(".skills/deploy/deploy.sh")));
        // Command should execute from the isolated skill directory
        Mockito.verify(backend).execute(eq(ctx), eq("cd '.skills/deploy' && './deploy.sh' --env staging"), any(ExecutionOptions.class));
    }

    @Test
    void nonExecutableSkillShouldReturnInstructions() throws Exception {
        ExecutionBackend backend = Mockito.mock(ExecutionBackend.class);

        SkillInfo skill = new SkillInfo("refactor", "Refactoring guide", "/skills/refactor/SKILL.md",
                "# Refactor\nStep-by-step guide");
        ExecutionContext ctx = new ExecutionContext("tenant-a", "s-1", null);
        SkillAgentTool tool = new SkillAgentTool(skill, backend, ctx);

        AgentToolResult result = tool.execute("tc-2", Map.of("input", "clean up auth"), null, null).get();

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.details();
        assertEquals(false, details.get("executable"));
        Mockito.verifyNoInteractions(backend);
    }

    @Test
    void executableSkillShouldSerializeStructuredArgsWhenInputMissing() throws Exception {
        Path skillDir = tempDir.resolve("skills/deploy");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), "---\nentrypoint: \"./deploy.sh\"\n---\n# Deploy");
        Files.writeString(skillDir.resolve("deploy.sh"), "#!/bin/bash\necho deployed");

        Path workspace = tempDir.resolve("workspaces/tenant-a/s-2");
        Files.createDirectories(workspace);

        ExecutionBackend backend = Mockito.mock(ExecutionBackend.class);
        Mockito.when(backend.getWorkspacePath("tenant-a", "s-2")).thenReturn(workspace);
        Mockito.when(backend.execute(any(ExecutionContext.class), anyString(), any(ExecutionOptions.class)))
                .thenReturn(new ExecutionResult(0, "ok", "", 10L, false, false));

        SkillInfo skill = new SkillInfo("deploy", "Deploy to staging",
                skillDir.resolve("SKILL.md").toString(),
                "# Deploy\nDeploy script", "./deploy.sh",
                "{\"type\":\"object\",\"properties\":{\"env\":{\"type\":\"string\"}}}");
        ExecutionContext ctx = new ExecutionContext("tenant-a", "s-2", null);
        SkillAgentTool tool = new SkillAgentTool(skill, backend, ctx);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("env", "staging");
        args.put("service", "api");
        tool.execute("tc-3", args, null, null).get();

        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<ExecutionOptions> optionsCaptor = ArgumentCaptor.forClass(ExecutionOptions.class);
        Mockito.verify(backend).execute(eq(ctx), commandCaptor.capture(), optionsCaptor.capture());

        assertEquals("cd '.skills/deploy' && './deploy.sh' '{\"env\":\"staging\",\"service\":\"api\"}'", commandCaptor.getValue());
        assertEquals(
                "{\"env\":\"staging\",\"service\":\"api\"}",
                optionsCaptor.getValue().envVars().get("PI_SKILL_ARGS_JSON")
        );
    }

    @Test
    void parameterSchemaShouldMergeArgsSchemaWithInput() {
        ExecutionBackend backend = Mockito.mock(ExecutionBackend.class);
        SkillInfo skill = new SkillInfo(
                "deploy",
                "Deploy to staging",
                "/skills/deploy/SKILL.md",
                "# Deploy",
                "./deploy.sh",
                "{\"type\":\"object\",\"properties\":{\"env\":{\"type\":\"string\"}}}"
        );

        SkillAgentTool tool = new SkillAgentTool(skill, backend, new ExecutionContext("tenant-a", "s-3", null));
        Map<String, Object> schema = tool.parametersSchema();

        assertEquals("object", schema.get("type"));
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("env"));
        assertTrue(properties.containsKey("input"));
    }
}
