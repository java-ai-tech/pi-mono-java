package com.glmapper.coding.core.tools.builtin;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.coding.core.config.BuiltinToolsProperties;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuiltinToolFactoryTest {

    @Test
    void shouldExposePiMonoDefaultBuiltinTools() {
        ExecutionBackend backend = Mockito.mock(ExecutionBackend.class);
        Mockito.when(backend.getWorkspacePath("tenant-a", "s-1")).thenReturn(Path.of("/tmp/workspace/tenant-a/s-1"));

        BuiltinToolsProperties properties = new BuiltinToolsProperties();
        BuiltinToolFactory factory = new BuiltinToolFactory(backend, properties);

        List<AgentTool> tools = factory.createDefaultTools(new ExecutionContext("tenant-a", "s-1", null));
        List<String> names = tools.stream().map(AgentTool::name).toList();

        assertEquals(List.of("read", "bash", "edit", "write"), names);
    }

    @Test
    void shouldResolveBuiltinToolByName() {
        ExecutionBackend backend = Mockito.mock(ExecutionBackend.class);
        Mockito.when(backend.getWorkspacePath("tenant-a", "s-1")).thenReturn(Path.of("/tmp/workspace/tenant-a/s-1"));

        BuiltinToolsProperties properties = new BuiltinToolsProperties();
        BuiltinToolFactory factory = new BuiltinToolFactory(backend, properties);

        Optional<AgentTool> grep = factory.resolveTool("grep", new ExecutionContext("tenant-a", "s-1", null));
        Optional<AgentTool> unknown = factory.resolveTool("unknown", new ExecutionContext("tenant-a", "s-1", null));

        assertTrue(grep.isPresent());
        assertEquals("grep", grep.get().name());
        assertFalse(unknown.isPresent());
    }
}
