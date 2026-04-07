package com.glmapper.coding.core.tools;

import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.coding.core.catalog.SkillInfo;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.execution.ExecutionOptions;
import com.glmapper.coding.core.execution.ExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
        // Skill files should be copied to workspace
        assertTrue(Files.exists(workspace.resolve("deploy.sh")));
        // Command uses the original relative entrypoint
        Mockito.verify(backend).execute(eq(ctx), eq("./deploy.sh --env staging"), any(ExecutionOptions.class));
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
}
