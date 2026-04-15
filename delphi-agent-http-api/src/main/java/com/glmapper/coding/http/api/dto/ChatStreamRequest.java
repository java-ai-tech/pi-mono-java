package com.glmapper.coding.http.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record ChatStreamRequest(
    @NotBlank String namespace,
    String prompt,
    String sessionId,
    String command,   // null=prompt, "steer"/"abort"/"compact"/"continue"/"fork"/"navigate"/"set_model"/"set_thinking"
    String mode,      // null/"agent"=direct loop, "orchestrated"=plan+execute
    String provider,
    String modelId,
    String systemPrompt,
    String projectKey,
    Map<String, Object> commandArgs
) {}
