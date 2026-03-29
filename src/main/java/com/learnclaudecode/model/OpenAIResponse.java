package com.learnclaudecode.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.learnclaudecode.common.JsonUtils;

import java.util.List;
import java.util.Map;

/**
 * OpenAI/DeepSeek Chat Completions API 响应的轻量映射。
 *
 * OpenAI 格式与 Anthropic 格式的主要区别：
 * - 响应结构：choices[].message 而非直接 content
 * - stop_reason：finish_reason 而非 stop_reason
 * - tool_calls：独立的 tool_calls 数组而非 content 中的 tool_use
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAIResponse(List<Choice> choices, Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(int index, Message message, String finish_reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(String role, String content, List<ToolCall> tool_calls) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ToolCall(
            String id,
            String type,
            FunctionCall function
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionCall(String name, String arguments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(int prompt_tokens, int completion_tokens, int total_tokens) {
    }

    /**
     * 转换为 Anthropic 兼容格式，便于上层统一处理。
     */
    public AnthropicResponse toAnthropicResponse() {
        if (choices == null || choices.isEmpty()) {
            return new AnthropicResponse(null, List.of());
        }

        Choice choice = choices.get(0);
        String stopReason = mapFinishReason(choice.finish_reason());

        List<Map<String, Object>> content = new java.util.ArrayList<>();

        // 添加文本内容
        if (choice.message().content() != null && !choice.message().content().isBlank()) {
            Map<String, Object> textContent = Map.of(
                    "type", "text",
                    "text", choice.message().content()
            );
            content.add(textContent);
        }

        // 添加工具调用
        if (choice.message().tool_calls() != null) {
            for (ToolCall tc : choice.message().tool_calls()) {
                Map<String, Object> toolUse = new java.util.LinkedHashMap<>();
                toolUse.put("type", "tool_use");
                toolUse.put("id", tc.id());
                toolUse.put("name", tc.function().name());
                // arguments 在 OpenAI 格式中是 JSON 字符串，需要解析为 Map
                try {
                    Map<String, Object> inputMap = JsonUtils.MAPPER.readValue(
                            tc.function().arguments(),
                            JsonUtils.MAPPER.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
                    );
                    toolUse.put("input", inputMap);
                } catch (Exception e) {
                    toolUse.put("input", tc.function().arguments());
                }
                content.add(toolUse);
            }
        }

        return new AnthropicResponse(stopReason, content);
    }

    private String mapFinishReason(String finishReason) {
        if (finishReason == null) return null;
        return switch (finishReason) {
            case "stop" -> "end_turn";
            case "tool_calls" -> "tool_use";
            case "length" -> "max_tokens";
            default -> finishReason;
        };
    }
}