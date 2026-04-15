package com.glmapper.coding.core.orchestration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.glmapper.agent.core.Agent;
import com.glmapper.agent.core.AgentEvent;
import com.glmapper.agent.core.AgentMessage;
import com.glmapper.agent.core.AgentOptions;
import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.BeforeToolCallResult;
import com.glmapper.ai.api.ContentBlock;
import com.glmapper.ai.api.AssistantMessageEvent;
import com.glmapper.ai.api.Model;
import com.glmapper.ai.api.StopReason;
import com.glmapper.ai.api.TextContent;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.ai.spi.ModelCatalog;
import com.glmapper.coding.core.tools.TaskPlanningTool;
import com.glmapper.coding.core.service.AgentToolFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class PlannerService {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final AiRuntime aiRuntime;
    private final ModelCatalog modelCatalog;
    private final AgentToolFactory agentToolFactory;
    private final ObjectMapper objectMapper;

    public PlannerService(
            AiRuntime aiRuntime,
            ModelCatalog modelCatalog,
            AgentToolFactory agentToolFactory
    ) {
        this.aiRuntime = aiRuntime;
        this.modelCatalog = modelCatalog;
        this.agentToolFactory = agentToolFactory;
        this.objectMapper = new ObjectMapper();
    }

    public ExecutionPlan createPlan(
            String namespace,
            String sessionId,
            String provider,
            String modelId,
            String systemPrompt,
            String userPrompt
    ) {
        return createPlan(namespace, sessionId, provider, modelId, systemPrompt, userPrompt, null);
    }

    public ExecutionPlan createPlan(
            String namespace,
            String sessionId,
            String provider,
            String modelId,
            String systemPrompt,
            String userPrompt,
            PlanningEventObserver observer
    ) {
        Model model = modelCatalog.get(provider, modelId)
                .orElseThrow(() -> new IllegalArgumentException("Model not found: " + provider + "/" + modelId));

        String plannerSessionId = sessionId == null || sessionId.isBlank()
                ? "planner-" + System.currentTimeMillis()
                : sessionId + "-planner";

        List<AgentTool> tools = agentToolFactory.createPlanningTools(namespace, plannerSessionId, userPrompt);
        Agent agent = new Agent(aiRuntime, model, planningOptions(java.util.Set.of()));
        agent.state().systemPrompt(buildPlanningSystemPrompt(systemPrompt));
        agent.state().tools(tools);
        PlanningEventObserver effectiveObserver = observer == null ? new PlanningEventObserver() { } : observer;
        agent.subscribe(event -> handlePlanningEvent(effectiveObserver, event));
        effectiveObserver.onPlanningStatus("规划 agent 已启动，正在分析请求和可用 skills。");

        try {
            agent.prompt(buildPlanningPrompt(userPrompt))
                    .orTimeout(45, TimeUnit.SECONDS)
                    .join();
        } catch (Exception planningError) {
            effectiveObserver.onPlanningStatus("规划 agent 未在预期时间内完成，回退到内置任务规划。");
            return fallbackPlan(userPrompt);
        }

        com.glmapper.agent.core.AgentAssistantMessage assistant = null;
        List<AgentMessage> messages = agent.state().messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            AgentMessage candidate = messages.get(i);
            if (candidate instanceof com.glmapper.agent.core.AgentAssistantMessage agentAssistantMessage) {
                assistant = agentAssistantMessage;
                break;
            }
        }

        if (assistant == null) {
            effectiveObserver.onPlanningStatus("规划 agent 没有返回有效消息，回退到内置任务规划。");
            return fallbackPlan(userPrompt);
        }
        if (assistant.stopReason() == StopReason.ERROR || assistant.stopReason() == StopReason.ABORTED) {
            effectiveObserver.onPlanningStatus("规划 agent 执行失败，回退到内置任务规划。");
            return fallbackPlan(userPrompt);
        }

        try {
            String assistantText = extractText(assistant.content());
            Map<String, Object> planPayload = parsePlanPayload(assistantText);
            List<PlanStep> steps = parseSteps(planPayload);
            if (steps.isEmpty()) {
                throw new IllegalStateException("Planning agent returned no executable steps");
            }

            String goal = stringValue(planPayload.get("goal"), userPrompt);
            ExecutionPlan plan = new ExecutionPlan(
                    stringValue(planPayload.get("planId"), "plan-" + UUID.randomUUID()),
                    goal,
                    steps
            );
            plan.status(PlanStatus.RUNNING);
            return plan;
        } catch (Exception parseError) {
            effectiveObserver.onPlanningStatus("规划结果不可解析，回退到内置任务规划。");
            return fallbackPlan(userPrompt);
        }
    }

    private ExecutionPlan fallbackPlan(String userPrompt) {
        if (isDirectImplementationRequest(userPrompt)) {
            ExecutionPlan compactPlan = new ExecutionPlan(
                    "plan-" + UUID.randomUUID(),
                    userPrompt,
                    List.of(
                            new PlanStep(
                                    "step-1",
                                    "实现代码",
                                    userPrompt,
                                    "给出完整可运行实现",
                                    StepExecutorType.AGENT,
                                    Map.of(
                                            "description", userPrompt,
                                            "expectedOutput", "给出完整可运行实现"
                                    )
                            ),
                            new PlanStep(
                                    "step-2",
                                    "验证结果",
                                    "给出示例输入输出，必要时补充简单测试",
                                    "确认实现可用并给出验证方式",
                                    StepExecutorType.AGENT,
                                    Map.of(
                                            "description", "验证刚才的实现，给出示例输入输出，必要时补充简单测试",
                                            "expectedOutput", "确认实现可用并给出验证方式"
                                    )
                            )
                    )
            );
            compactPlan.status(PlanStatus.RUNNING);
            return compactPlan;
        }

        TaskPlanningTool fallbackPlanner = new TaskPlanningTool();
        Map<String, Object> args = Map.of("task", userPrompt);
        var result = fallbackPlanner.execute(
                "fallback-planner",
                args,
                partial -> { },
                new java.util.concurrent.CancellationException("fallback-planner")
        ).join();

        Object details = result.details();
        Object rawSteps = details instanceof Map<?, ?> detailMap ? detailMap.get("steps") : null;
        List<PlanStep> steps = fallbackSteps(rawSteps);
        if (steps.isEmpty()) {
            steps = List.of(new PlanStep(
                    "step-1",
                    "直接完成任务",
                    userPrompt,
                    "给出完整可执行结果",
                    StepExecutorType.AGENT,
                    Map.of(
                            "description", userPrompt,
                            "expectedOutput", "给出完整可执行结果"
                    )
            ));
        }

        ExecutionPlan plan = new ExecutionPlan("plan-" + UUID.randomUUID(), userPrompt, steps);
        plan.status(PlanStatus.RUNNING);
        return plan;
    }

    private boolean isDirectImplementationRequest(String userPrompt) {
        if (userPrompt == null) {
            return false;
        }
        String prompt = userPrompt.toLowerCase(Locale.ROOT);
        return (prompt.contains("实现")
                || prompt.contains("编写")
                || prompt.contains("写一个")
                || prompt.contains("算法")
                || prompt.contains("函数")
                || prompt.contains("代码")
                || prompt.contains("implement")
                || prompt.contains("write"))
                && !prompt.contains("规划")
                && !prompt.contains("计划")
                && !prompt.contains("review")
                && !prompt.contains("审查")
                && !prompt.contains("git");
    }

    private List<PlanStep> fallbackSteps(Object rawSteps) {
        if (!(rawSteps instanceof List<?> stepList)) {
            return List.of();
        }

        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < stepList.size(); i++) {
            Object item = stepList.get(i);
            if (!(item instanceof Map<?, ?> stepMap)) {
                continue;
            }
            String id = "step-" + (i + 1);
            String title = stringValue(stepMap.get("title"), "步骤 " + (i + 1));
            String description = stringValue(stepMap.get("description"), "");
            String successCriteria = stringValue(stepMap.get("output"), "完成当前步骤");
            steps.add(new PlanStep(
                    id,
                    title,
                    description,
                    successCriteria,
                    StepExecutorType.AGENT,
                    Map.of(
                            "description", description,
                            "expectedOutput", successCriteria
                    )
            ));
        }
        return steps;
    }

    private String buildPlanningSystemPrompt(String userSystemPrompt) {
        StringBuilder builder = new StringBuilder();
        if (userSystemPrompt != null && !userSystemPrompt.isBlank()) {
            builder.append(userSystemPrompt.trim()).append("\n\n");
        }
        builder.append("You are the planning phase of delphi-agent.\n");
        builder.append("You may use the available tools and skills to understand the task.\n");
        builder.append("Your only job is to return a structured execution plan.\n");
        builder.append("Do not execute the whole task directly.\n");
        builder.append("Output valid JSON only, with no markdown fences and no extra commentary.\n");
        return builder.toString();
    }

    private String buildPlanningPrompt(String userPrompt) {
        return """
                基于当前用户请求和可用 skills/tools，生成一个结构化任务清单。

                用户请求：
                %s

                返回 JSON，格式必须如下：
                {
                  "goal": "总体目标",
                  "steps": [
                    {
                      "id": "step-1",
                      "title": "步骤标题",
                      "description": "步骤说明",
                      "successCriteria": "完成标准",
                      "executorType": "AGENT|SKILL|BASH",
                      "payload": {
                        "description": "给 AGENT 的补充说明",
                        "toolName": "给 SKILL 用的工具名",
                        "input": "给 SKILL 的输入字符串",
                        "command": "给 BASH 的命令"
                      }
                    }
                  ]
                }

                规则：
                1. 如果当前可用 skill 明显适合某一步，优先把该步骤规划成 SKILL，并在 payload 中写 toolName。
                2. 如果需要开放式分析、编码或综合判断，使用 AGENT。
                3. 只有在明确需要命令执行时才使用 BASH。
                4. 对于“实现一个函数/算法/小功能”这类直接交付型请求，优先生成最短可执行计划，通常 1 到 3 步，不要默认拆成“需求分析/设计方案/代码审查/git流程”等泛化软件流程。
                5. 只有当用户明确要求评审、git 操作、发布、规划说明时，才生成对应步骤。
                6. 返回纯 JSON，不要加解释。
                """.formatted(userPrompt);
    }

    private Map<String, Object> parsePlanPayload(String rawText) {
        String json = extractJson(rawText);
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException("Planning agent returned invalid JSON plan: " + rawText, e);
        }
    }

    private List<PlanStep> parseSteps(Map<String, Object> payload) {
        Object rawSteps = payload.get("steps");
        if (!(rawSteps instanceof List<?> stepList)) {
            return List.of();
        }

        List<PlanStep> steps = new ArrayList<>();
        for (int i = 0; i < stepList.size(); i++) {
            Object item = stepList.get(i);
            if (!(item instanceof Map<?, ?> stepMap)) {
                continue;
            }

            String id = stringValue(stepMap.get("id"), "step-" + (i + 1));
            String title = stringValue(stepMap.get("title"), "步骤 " + (i + 1));
            String description = stringValue(stepMap.get("description"), "");
            String successCriteria = stringValue(stepMap.get("successCriteria"), "完成当前步骤");
            StepExecutorType executorType = parseExecutorType(stepMap.get("executorType"));
            Map<String, Object> stepPayload = toPayloadMap(stepMap.get("payload"), description, successCriteria);

            steps.add(new PlanStep(id, title, description, successCriteria, executorType, stepPayload));
        }
        return steps;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toPayloadMap(Object payload, String description, String successCriteria) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        if (payload instanceof Map<?, ?> payloadMap) {
            for (Map.Entry<?, ?> entry : payloadMap.entrySet()) {
                if (entry.getKey() != null) {
                    normalized.put(String.valueOf(entry.getKey()), entry.getValue());
                }
            }
        }
        normalized.putIfAbsent("description", description);
        normalized.putIfAbsent("expectedOutput", successCriteria);
        return normalized;
    }

    private StepExecutorType parseExecutorType(Object value) {
        if (value == null) {
            return StepExecutorType.AGENT;
        }
        try {
            return StepExecutorType.valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return StepExecutorType.AGENT;
        }
    }

    private String extractText(List<ContentBlock> content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextContent text && text.text() != null) {
                sb.append(text.text());
            }
        }
        return sb.toString();
    }

    private String extractJson(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalStateException("Planning agent returned empty content");
        }
        String trimmed = rawText.trim();
        if (trimmed.startsWith("```")) {
            int firstLineEnd = trimmed.indexOf('\n');
            if (firstLineEnd >= 0) {
                trimmed = trimmed.substring(firstLineEnd + 1);
            }
            int fenceEnd = trimmed.lastIndexOf("```");
            if (fenceEnd >= 0) {
                trimmed = trimmed.substring(0, fenceEnd).trim();
            }
        }

        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("Planning agent did not return a JSON object");
        }
        return trimmed.substring(start, end + 1);
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private AgentOptions planningOptions(Set<String> blockedExecutableTools) {
        AgentOptions defaults = AgentOptions.defaults();
        return new AgentOptions(
                defaults.convertToLlm(),
                defaults.transformContext(),
                defaults.steeringMessagesSupplier(),
                defaults.followUpMessagesSupplier(),
                defaults.toolExecutionMode(),
                (context, ignored) -> {
                    if (blockedExecutableTools.contains(context.toolCall().name())) {
                        return CompletableFuture.completedFuture(new BeforeToolCallResult(
                                true,
                                "Executable skills are disabled during planning"
                        ));
                    }
                    return CompletableFuture.completedFuture(null);
                },
                defaults.afterToolCall(),
                defaults.apiKeyResolver(),
                defaults.streamOptions()
        );
    }

    private void handlePlanningEvent(PlanningEventObserver observer, AgentEvent event) {
        if (event instanceof AgentEvent.MessageStart messageStart
                && messageStart.message() instanceof com.glmapper.agent.core.AgentAssistantMessage) {
            observer.onPlanningStatus("规划模型开始生成任务清单。");
            return;
        }
        if (event instanceof AgentEvent.MessageUpdate messageUpdate
                && messageUpdate.message() instanceof com.glmapper.agent.core.AgentAssistantMessage) {
            AssistantMessageEvent assistantEvent = messageUpdate.assistantMessageEvent();
            if (assistantEvent instanceof AssistantMessageEvent.ThinkingStart
                    || assistantEvent instanceof AssistantMessageEvent.ThinkingDelta) {
                observer.onPlanningStatus("规划模型正在分析任务。");
                return;
            }
            if (assistantEvent instanceof AssistantMessageEvent.ToolCallStart
                    || assistantEvent instanceof AssistantMessageEvent.ToolCallDelta) {
                observer.onPlanningStatus("规划模型正在选择合适的工具和 skills。");
                return;
            }
            if (assistantEvent instanceof AssistantMessageEvent.TextStart
                    || assistantEvent instanceof AssistantMessageEvent.TextDelta) {
                observer.onPlanningStatus("规划模型正在生成结构化任务清单。");
                return;
            }
        }
        if (event instanceof AgentEvent.ToolExecutionStart toolStart) {
            observer.onToolStart(toolStart.toolCallId(), toolStart.toolName());
            return;
        }
        if (event instanceof AgentEvent.ToolExecutionEnd toolEnd) {
            observer.onToolEnd(
                    toolEnd.toolCallId(),
                    toolEnd.toolName(),
                    extractText(toolEnd.result().content()),
                    toolEnd.isError()
            );
        }
    }
}
