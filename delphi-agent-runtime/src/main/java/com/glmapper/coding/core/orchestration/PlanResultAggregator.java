package com.glmapper.coding.core.orchestration;

import org.springframework.stereotype.Service;

@Service
public class PlanResultAggregator {
    public String summarize(ExecutionPlan plan) {
        StringBuilder builder = new StringBuilder();
        builder.append("任务执行").append(plan.status() == PlanStatus.SUCCEEDED ? "完成" : "未完成").append("。\n");

        for (int i = 0; i < plan.steps().size(); i++) {
            PlanStep step = plan.steps().get(i);
            builder.append(i + 1).append(". ").append(step.title()).append(" - ").append(step.status());
            if (step.resultSummary() != null && !step.resultSummary().isBlank()) {
                builder.append("\n").append(step.resultSummary().trim());
            }
            if (step.errorMessage() != null && !step.errorMessage().isBlank()) {
                builder.append("\n错误：").append(step.errorMessage().trim());
            }
            builder.append("\n\n");
        }

        if (plan.errorMessage() != null && !plan.errorMessage().isBlank()) {
            builder.append("最终错误：").append(plan.errorMessage()).append("\n");
        }
        return builder.toString().trim();
    }
}
