package com.glmapper.coding.core.service;

import com.glmapper.ai.api.*;
import com.glmapper.ai.spi.AiRuntime;
import com.glmapper.coding.core.mongo.SessionEntryDocument;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Summarizes abandoned conversation branches using LLM.
 */
@Component
public class BranchSummarizer {
    private static final int TOKEN_BUDGET = 8000;
    private static final int CHARS_PER_TOKEN = 4;
    private static final int CHAR_BUDGET = TOKEN_BUDGET * CHARS_PER_TOKEN;

    /**
     * Summarize an abandoned branch path into a structured summary.
     *
     * @return summary text, or null on failure
     */
    public String summarizeBranch(
            List<SessionEntryDocument> abandonedPath,
            Model model,
            AiRuntime aiRuntime
    ) {
        try {
            String transcript = buildTranscript(abandonedPath);
            if (transcript == null || transcript.isBlank()) {
                return null;
            }

            String instruction = "你是一个对话分支摘要引擎。请将被放弃的对话分支准确总结为结构化摘要。"
                    + "不要编造事实。使用中文输出。";
            String prompt = "请将以下被放弃的对话分支总结为结构化摘要，格式如下：\n"
                    + "- 目标：用户在这个分支中想要达成什么\n"
                    + "- 已完成：已经完成的工作\n"
                    + "- 进行中：正在进行但未完成的工作\n"
                    + "- 关键决策：做出的重要决策或发现\n\n"
                    + transcript;

            Context context = new Context(
                    instruction,
                    List.of(new UserMessage(List.of(new TextContent(prompt, null)), System.currentTimeMillis())),
                    List.of()
            );

            StreamOptions streamOptions = new StreamOptions(
                    0.1,
                    800,
                    null,
                    "sse",
                    "short",
                    null,
                    60_000,
                    Map.of(),
                    Map.of("purpose", "branch-summary"),
                    ThinkingLevel.OFF,
                    Map.of()
            );

            AssistantMessage response = aiRuntime.completeSimple(model, context, streamOptions).join();
            return extractText(response.content());
        } catch (Exception e) {
            return null;
        }
    }

    private String buildTranscript(List<SessionEntryDocument> entries) {
        if (entries == null || entries.isEmpty()) {
            return null;
        }

        StringBuilder transcript = new StringBuilder();
        int charCount = 0;

        for (SessionEntryDocument entry : entries) {
            Map<String, Object> payload = entry.getPayload();
            if (payload == null) {
                continue;
            }

            String role = (String) payload.get("role");
            String text = extractPayloadText(payload);
            if (text == null || text.isBlank()) {
                continue;
            }

            String normalized = text.replace('\n', ' ').trim();
            if (normalized.length() > 600) {
                normalized = normalized.substring(0, 600) + "...";
            }

            String line = (role != null ? role : "unknown") + ": " + normalized + "\n";
            if (charCount + line.length() > CHAR_BUDGET) {
                break;
            }
            transcript.append(line);
            charCount += line.length();
        }

        return transcript.toString();
    }

    @SuppressWarnings("unchecked")
    private String extractPayloadText(Map<String, Object> payload) {
        Object contentObj = payload.get("content");
        if (contentObj instanceof List<?> contentList) {
            StringBuilder sb = new StringBuilder();
            for (Object item : contentList) {
                if (item instanceof Map<?, ?> block) {
                    String type = (String) block.get("type");
                    if ("text".equals(type)) {
                        String text = (String) block.get("text");
                        if (text != null) {
                            if (!sb.isEmpty()) {
                                sb.append(" ");
                            }
                            sb.append(text);
                        }
                    }
                }
            }
            return sb.toString();
        }
        return null;
    }

    private String extractText(List<ContentBlock> content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : content) {
            if (block instanceof TextContent text) {
                if (!sb.isEmpty()) {
                    sb.append(" ");
                }
                sb.append(text.text());
            }
        }
        return sb.isEmpty() ? null : sb.toString().trim();
    }
}
