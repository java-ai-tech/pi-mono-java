package com.glmapper.coding.core.rpc;

import com.glmapper.agent.core.AgentMessage;
import com.glmapper.agent.core.QueueMode;
import com.glmapper.ai.api.Model;
import com.glmapper.coding.core.catalog.ResourceCatalogService;
import com.glmapper.coding.core.catalog.SlashCommandInfo;
import com.glmapper.coding.core.domain.SessionStateSnapshot;
import com.glmapper.coding.core.execution.ExecutionAuditLogger;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.execution.ExecutionOptions;
import com.glmapper.coding.core.execution.ExecutionResult;
import com.glmapper.coding.core.extensions.ExtensionRuntime;
import com.glmapper.coding.core.mongo.SessionDocument;
import com.glmapper.coding.core.service.AgentSessionRuntime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class RpcCommandProcessorContractTest {
    private RpcCommandProcessor newProcessor(AgentSessionRuntime runtime,
                                             ResourceCatalogService catalog,
                                             ExtensionRuntime extensions) {
        ExecutionBackend executionBackend = Mockito.mock(ExecutionBackend.class);
        ExecutionAuditLogger auditLogger = Mockito.mock(ExecutionAuditLogger.class);
        Mockito.when(executionBackend.execute(any(ExecutionContext.class), anyString(), any(ExecutionOptions.class)))
                .thenReturn(new ExecutionResult(0, "", "", 1L, false, false));
        return new RpcCommandProcessor(runtime, catalog, extensions, executionBackend, auditLogger);
    }

    @Test
    void getStateShouldReturnRpcCompatibleShape() {
        AgentSessionRuntime runtime = Mockito.mock(AgentSessionRuntime.class);
        ResourceCatalogService catalog = Mockito.mock(ResourceCatalogService.class);
        ExtensionRuntime extensions = Mockito.mock(ExtensionRuntime.class);

        SessionDocument session = new SessionDocument();
        session.setId("s-1");
        session.setSessionName("demo");
        session.setModelProvider("openai");
        session.setModelId("gpt-4o-mini");
        session.setNamespace("default");
        session.setSteeringMode(QueueMode.ALL.name());
        session.setFollowUpMode(QueueMode.ONE_AT_A_TIME.name());
        session.setAutoCompactionEnabled(true);

        Mockito.when(runtime.session("s-1", "default")).thenReturn(session);
        Mockito.when(runtime.state("s-1", "default")).thenReturn(new SessionStateSnapshot(
                "s-1", "openai", "gpt-4o-mini", "LOW", false, null, List.<AgentMessage>of()
        ));

        RpcCommandProcessor processor = newProcessor(runtime, catalog, extensions);
        RpcCommandRequest request = new RpcCommandRequest();
        request.setId("1");
        request.setType("get_state");
        request.setSessionId("s-1");
        request.setNamespace("default");

        RpcCommandResponse response = processor.execute(request);
        assertTrue(response.success());
        assertEquals("response", response.type());
        assertEquals("get_state", response.command());

        RpcSessionState state = (RpcSessionState) response.data();
        assertEquals("s-1", state.sessionId());
        assertEquals("demo", state.sessionName());
        assertEquals("openai", state.provider());
        assertEquals("gpt-4o-mini", state.modelId());
        assertEquals("ALL", state.steeringMode());
        assertEquals("ONE_AT_A_TIME", state.followUpMode());
        assertTrue(state.autoCompactionEnabled());
    }

    @Test
    void getCommandsShouldMergeCatalogAndExtensionCommands() {
        AgentSessionRuntime runtime = Mockito.mock(AgentSessionRuntime.class);
        ResourceCatalogService catalog = Mockito.mock(ResourceCatalogService.class);
        ExtensionRuntime extensions = Mockito.mock(ExtensionRuntime.class);

        Mockito.when(extensions.slashCommands()).thenReturn(List.of(
                new SlashCommandInfo("ext-cmd", "ext", "extension", "user", null)
        ));
        Mockito.when(catalog.slashCommands(anyList())).thenAnswer(invocation -> {
            List<SlashCommandInfo> extensionCommands = invocation.getArgument(0);
            return List.of(
                    new SlashCommandInfo("prompt-cmd", "prompt", "prompt", "project", "/tmp/prompt.prompt.md"),
                    extensionCommands.get(0)
            );
        });

        RpcCommandProcessor processor = newProcessor(runtime, catalog, extensions);
        RpcCommandRequest request = new RpcCommandRequest();
        request.setType("get_commands");

        RpcCommandResponse response = processor.execute(request);
        assertTrue(response.success());
        @SuppressWarnings("unchecked")
        List<SlashCommandInfo> commands = (List<SlashCommandInfo>) response.data();
        assertEquals(2, commands.size());
        assertEquals("prompt-cmd", commands.get(0).name());
        assertEquals("ext-cmd", commands.get(1).name());
    }

    @Test
    void setSteeringModeShouldMapOneAtATime() {
        AgentSessionRuntime runtime = Mockito.mock(AgentSessionRuntime.class);
        ResourceCatalogService catalog = Mockito.mock(ResourceCatalogService.class);
        ExtensionRuntime extensions = Mockito.mock(ExtensionRuntime.class);

        SessionDocument session = new SessionDocument();
        session.setId("s-1");
        session.setNamespace("default");

        RpcCommandProcessor processor = newProcessor(runtime, catalog, extensions);
        RpcCommandRequest request = new RpcCommandRequest();
        request.setType("set_steering_mode");
        request.setSessionId("s-1");
        request.setNamespace("default");
        request.setMode("one-at-a-time");

        RpcCommandResponse response = processor.execute(request);

        assertTrue(response.success());
        Mockito.verify(runtime).setSteeringMode(eq("s-1"), eq("default"), eq(QueueMode.ONE_AT_A_TIME));
        assertEquals(Map.of("mode", "one-at-a-time"), response.data());
    }

    @Test
    void bashShouldExecuteWithinNamespaceContext() {
        AgentSessionRuntime runtime = Mockito.mock(AgentSessionRuntime.class);
        ResourceCatalogService catalog = Mockito.mock(ResourceCatalogService.class);
        ExtensionRuntime extensions = Mockito.mock(ExtensionRuntime.class);
        ExecutionBackend executionBackend = Mockito.mock(ExecutionBackend.class);
        ExecutionAuditLogger auditLogger = Mockito.mock(ExecutionAuditLogger.class);
        Mockito.when(executionBackend.execute(any(ExecutionContext.class), anyString(), any(ExecutionOptions.class)))
                .thenReturn(new ExecutionResult(0, "ok", "", 5L, false, false));

        SessionDocument session = new SessionDocument();
        session.setId("s-1");
        session.setNamespace("tenant-a");
        Mockito.when(runtime.session("s-1", "tenant-a")).thenReturn(session);

        RpcCommandProcessor processor = new RpcCommandProcessor(runtime, catalog, extensions, executionBackend, auditLogger);
        RpcCommandRequest request = new RpcCommandRequest();
        request.setId("1");
        request.setType("bash");
        request.setSessionId("s-1");
        request.setNamespace("tenant-a");
        request.setCommand("pwd");

        RpcCommandResponse response = processor.execute(request);

        assertTrue(response.success());
        ArgumentCaptor<ExecutionContext> captor = ArgumentCaptor.forClass(ExecutionContext.class);
        Mockito.verify(executionBackend).execute(captor.capture(), eq("pwd"), any(ExecutionOptions.class));
        assertEquals("tenant-a", captor.getValue().namespace());
        assertEquals("s-1", captor.getValue().sessionId());
    }
}
