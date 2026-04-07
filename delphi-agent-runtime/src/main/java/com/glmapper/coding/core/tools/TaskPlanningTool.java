package com.glmapper.coding.core.tools;

import com.glmapper.agent.core.AgentTool;
import com.glmapper.agent.core.AgentToolResult;
import com.glmapper.agent.core.AgentToolUpdateCallback;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class TaskPlanningTool implements AgentTool {

    @Override
    public String name() {
        return "task_planning";
    }

    @Override
    public String label() {
        return "任务规划";
    }

    @Override
    public String description() {
        return "将复杂任务分解为多个可执行的子任务步骤。输入任务描述，返回结构化的任务规划。";
    }

    @Override
    public Map<String, Object> parametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "task", Map.of(
                                "type", "string",
                                "description", "需要规划的任务描述"
                        ),
                        "context", Map.of(
                                "type", "string",
                                "description", "任务的上下文信息（可选）"
                        )
                ),
                "required", List.of("task")
        );
    }

    @Override
    public CompletableFuture<AgentToolResult> execute(
            String toolCallId,
            Map<String, Object> params,
            AgentToolUpdateCallback onUpdate,
            java.util.concurrent.CancellationException cancellation
    ) {
        return CompletableFuture.supplyAsync(() -> {
            String task = (String) params.get("task");
            String context = (String) params.getOrDefault("context", "");

            // 模拟任务规划过程
            List<Map<String, String>> steps = planTask(task, context);

            StringBuilder result = new StringBuilder();
            result.append("📋 任务规划结果：\n\n");
            result.append("**任务**：").append(task).append("\n\n");
            result.append("**执行步骤**：\n\n");

            for (int i = 0; i < steps.size(); i++) {
                Map<String, String> step = steps.get(i);
                result.append(String.format("%d. **%s**\n", i + 1, step.get("title")));
                result.append(String.format("   - 描述：%s\n", step.get("description")));
                result.append(String.format("   - 预期输出：%s\n\n", step.get("output")));
            }

            return new AgentToolResult(
                    List.of(new com.glmapper.ai.api.TextContent(result.toString(), null)),
                    Map.of("steps", steps)
            );
        });
    }

    private List<Map<String, String>> planTask(String task, String context) {
        List<Map<String, String>> steps = new ArrayList<>();

        // 简单的启发式规划逻辑
        if (task.contains("实现") || task.contains("开发") || task.contains("编写")) {
            steps.add(Map.of(
                    "title", "需求分析",
                    "description", "明确功能需求、输入输出、边界条件",
                    "output", "需求文档或功能清单"
            ));
            steps.add(Map.of(
                    "title", "设计方案",
                    "description", "设计数据结构、算法流程、接口定义",
                    "output", "设计文档或伪代码"
            ));
            steps.add(Map.of(
                    "title", "编码实现",
                    "description", "根据设计方案编写代码",
                    "output", "可运行的代码"
            ));
            steps.add(Map.of(
                    "title", "测试验证",
                    "description", "编写测试用例，验证功能正确性",
                    "output", "测试报告"
            ));
        } else if (task.contains("分析") || task.contains("研究")) {
            steps.add(Map.of(
                    "title", "信息收集",
                    "description", "收集相关资料、文档、数据",
                    "output", "资料清单"
            ));
            steps.add(Map.of(
                    "title", "数据分析",
                    "description", "分析数据，提取关键信息",
                    "output", "分析结果"
            ));
            steps.add(Map.of(
                    "title", "总结报告",
                    "description", "整理分析结果，形成报告",
                    "output", "分析报告"
            ));
        } else {
            steps.add(Map.of(
                    "title", "任务分解",
                    "description", "将任务分解为更小的子任务",
                    "output", "子任务列表"
            ));
            steps.add(Map.of(
                    "title", "逐步执行",
                    "description", "按顺序执行各个子任务",
                    "output", "执行结果"
            ));
            steps.add(Map.of(
                    "title", "结果整合",
                    "description", "整合各子任务结果",
                    "output", "最终结果"
            ));
        }

        return steps;
    }
}
