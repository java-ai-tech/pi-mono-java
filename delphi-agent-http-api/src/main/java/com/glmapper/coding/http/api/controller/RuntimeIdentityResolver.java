package com.glmapper.coding.http.api.controller;

import com.glmapper.coding.http.api.dto.ChatStreamRequest;
import org.springframework.stereotype.Component;

@Component
public class RuntimeIdentityResolver {

    public RuntimeIdentity resolve(String tenantIdHeader, String userIdHeader, ChatStreamRequest request) {
        String tenantId = requireHeader("X-Tenant-Id", tenantIdHeader);
        String userId = requireHeader("X-User-Id", userIdHeader);

        if (request.namespace() != null && !request.namespace().isBlank()
                && !tenantId.equals(request.namespace())) {
            throw new SecurityException("namespace is not authorized for tenant");
        }
        return new RuntimeIdentity(tenantId, tenantId, userId);
    }

    private String requireHeader(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new SecurityException(name + " is required");
        }
        return value.trim();
    }
}

