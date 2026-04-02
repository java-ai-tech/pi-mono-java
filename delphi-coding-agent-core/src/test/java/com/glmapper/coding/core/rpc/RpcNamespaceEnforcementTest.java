package com.glmapper.coding.core.rpc;

import com.glmapper.coding.core.catalog.ResourceCatalogService;
import com.glmapper.coding.core.execution.ExecutionAuditLogger;
import com.glmapper.coding.core.execution.ExecutionBackend;
import com.glmapper.coding.core.execution.ExecutionContext;
import com.glmapper.coding.core.execution.ExecutionOptions;
import com.glmapper.coding.core.execution.ExecutionResult;
import com.glmapper.coding.core.extensions.ExtensionRuntime;
import com.glmapper.coding.core.mongo.SessionDocument;
import com.glmapper.coding.core.service.AgentSessionRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

class RpcNamespaceEnforcementTest {
    private AgentSessionRuntime runtime;
    private RpcCommandProcessor processor;

    @BeforeEach
    void setUp() {
        runtime = Mockito.mock(AgentSessionRuntime.class);
        ResourceCatalogService catalog = Mockito.mock(ResourceCatalogService.class);
        ExtensionRuntime extensions = Mockito.mock(ExtensionRuntime.class);
        ExecutionBackend executionBackend = Mockito.mock(ExecutionBackend.class);
        ExecutionAuditLogger auditLogger = Mockito.mock(ExecutionAuditLogger.class);
        Mockito.when(executionBackend.execute(any(ExecutionContext.class), anyString(), any(ExecutionOptions.class)))
                .thenReturn(new ExecutionResult(0, "", "", 1L, false, false));
        processor = new RpcCommandProcessor(runtime, catalog, extensions, executionBackend, auditLogger);
    }

    @Test
    void getStateShouldRejectMissingNamespace() {
        RpcCommandRequest request = new RpcCommandRequest();
        request.setId("1");
        request.setType("get_state");
        request.setSessionId("s-1");

        RpcCommandResponse response = processor.execute(request);
        assertFalse(response.success());
        assertTrue(response.error().contains("namespace is required"));
    }

    @Test
    void bashShouldRejectMissingNamespace() {
        RpcCommandRequest request = new RpcCommandRequest();
        request.setId("2");
        request.setType("bash");
        request.setSessionId("s-1");
        request.setCommand("pwd");

        RpcCommandResponse response = processor.execute(request);
        assertFalse(response.success());
        assertTrue(response.error().contains("namespace is required"));
    }

    @Test
    void forkShouldRejectMissingNamespace() {
        RpcCommandRequest request = new RpcCommandRequest();
        request.setId("3");
        request.setType("fork");
        request.setSessionId("s-1");

        RpcCommandResponse response = processor.execute(request);
        assertFalse(response.success());
        assertTrue(response.error().contains("namespace is required"));
    }

    @Test
    void crossTenantAccessShouldBeRejected() {
        Mockito.when(runtime.session("s-1", "tenant-b"))
                .thenThrow(new IllegalArgumentException("Namespace mismatch: session belongs to tenant-a"));

        RpcCommandRequest request = new RpcCommandRequest();
        request.setId("4");
        request.setType("get_state");
        request.setSessionId("s-1");
        request.setNamespace("tenant-b");

        RpcCommandResponse response = processor.execute(request);
        assertFalse(response.success());
        assertTrue(response.error().contains("Namespace mismatch"));
    }
}
