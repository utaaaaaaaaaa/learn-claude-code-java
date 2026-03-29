package com.learnclaudecode.common;

import com.learnclaudecode.model.AnthropicResponse;
import com.learnclaudecode.model.ChatMessage;
import com.learnclaudecode.model.OpenAIResponse;
import com.learnclaudecode.model.ToolSpec;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * DeepSeek/OpenAI Chat Completions API 调用器。
 *
 * DeepSeek 使用 OpenAI 格式的 API，与 Anthropic 格式有以下主要区别：
 * 1. 端点路径：/v1/chat/completions
 * 2. 请求体：messages 在顶层，system 作为第一条 message
 * 3. 响应体：choices[].message 结构
 * 4. 工具调用：tool_calls 数组而非 content 中的 tool_use
 * 5. 工具结果：必须作为独立的 role=tool 消息
 *
 * 本类负责将上层统一接口转换为 OpenAI 格式，并将响应转回 Anthropic 格式。
 */
public class DeepSeekClient implements LLMClient {
    private final EnvConfig config;
    private final HttpClient httpClient;

    /**
     * 根据环境配置初始化 DeepSeek 客户端。
     *
     * @param config 环境配置
     */
    public DeepSeekClient(EnvConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 调用 DeepSeek Chat Completions API 生成下一轮响应。
     *
     * @param system    system prompt
     * @param messages  对话历史
     * @param tools     可用工具定义
     * @param maxTokens 最大输出 token 数
     * @return 模型响应（转换为 Anthropic 兼容格式）
     */
    public AnthropicResponse createMessage(String system, List<?> messages, List<?> tools, int maxTokens) {
        Map<String, Object> payload = new LinkedHashMap<>();

        // 模型 ID
        payload.put("model", config.getDeepSeekModelId());

        // 构造 OpenAI 格式的 messages 数组
        List<Map<String, Object>> openaiMessages = new ArrayList<>();

        // system prompt 作为第一条 system 消息
        if (system != null && !system.isBlank()) {
            openaiMessages.add(Map.of("role", "system", "content", system));
        }

        // 转换对话历史
        if (messages != null) {
            for (Object msg : messages) {
                if (msg instanceof ChatMessage cm) {
                    // 一条 ChatMessage 可能需要展开为多条 OpenAI 消息
                    List<Map<String, Object>> expanded = expandMessage(cm);
                    openaiMessages.addAll(expanded);
                } else if (msg instanceof Map<?, ?> map) {
                    openaiMessages.add(convertMapMessage(map));
                }
            }
        }
        payload.put("messages", openaiMessages);

        // 最大输出 token
        payload.put("max_tokens", maxTokens);

        // 工具定义转换
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", convertTools(tools));
        }

        // 构建请求
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.getDeepSeekBaseUrl().replaceAll("/$", "") + "/v1/chat/completions"))
                .timeout(Duration.ofSeconds(180))
                .header("content-type", "application/json")
                .header("Authorization", "Bearer " + config.getDeepSeekApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(payload)));

        try {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                throw new IllegalStateException("DeepSeek API 调用失败: HTTP " + response.statusCode() + "\n" + response.body());
            }

            // 解析 OpenAI 格式响应并转换为 Anthropic 格式
            OpenAIResponse openaiResponse = JsonUtils.fromJson(response.body(), OpenAIResponse.class);
            return openaiResponse.toAnthropicResponse();
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("DeepSeek API 调用失败", e);
        }
    }

    /**
     * 将一条 ChatMessage 展开为多条 OpenAI 格式消息。
     *
     * OpenAI 格式要求：
     * - assistant 的 tool_use 放在 tool_calls 数组中
     * - tool_result 必须作为独立的 role=tool 消息，不能嵌套在 user 消息中
     *
     * @param msg Anthropic 格式的消息
     * @return 展开后的 OpenAI 格式消息列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> expandMessage(ChatMessage msg) {
        List<Map<String, Object>> result = new ArrayList<>();

        Object content = msg.content();
        String role = msg.role();

        if (content instanceof String text) {
            // 简单文本消息，直接转换
            result.add(Map.of("role", role, "content", text));
        } else if (content instanceof List<?> list) {
            if ("assistant".equals(role)) {
                // assistant 消息：提取文本和 tool_calls，合并为一条消息
                Map<String, Object> assistantMsg = convertAssistantMessage(list);
                result.add(assistantMsg);
            } else if ("user".equals(role)) {
                // user 消息：检查是否包含 tool_result
                List<Map<String, Object>> toolResults = new ArrayList<>();
                List<Object> otherContent = new ArrayList<>();

                for (Object item : list) {
                    if (item instanceof Map<?, ?> block) {
                        String type = String.valueOf(block.get("type"));
                        if ("tool_result".equals(type)) {
                            // tool_result 需要展开为独立的 tool 消息
                            Map<String, Object> toolMsg = new LinkedHashMap<>();
                            toolMsg.put("role", "tool");
                            toolMsg.put("tool_call_id", String.valueOf(block.get("tool_use_id")));
                            toolMsg.put("content", String.valueOf(block.get("content")));
                            toolResults.add(toolMsg);
                        } else {
                            otherContent.add(block);
                        }
                    }
                }

                // 如果只有 tool_result，直接返回 tool 消息列表
                if (otherContent.isEmpty()) {
                    result.addAll(toolResults);
                } else {
                    // 如果有其他内容，先添加 user 消息，再添加 tool 消息
                    result.add(Map.of("role", "user", "content", otherContent));
                    result.addAll(toolResults);
                }
            } else {
                // 其他角色，直接使用原始内容
                result.add(Map.of("role", role, "content", list));
            }
        } else {
            result.add(Map.of("role", role, "content", content));
        }

        return result;
    }

    /**
     * 将 assistant 的 content 列表转换为 OpenAI 格式的 assistant 消息。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertAssistantMessage(List<?> list) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", "assistant");

        String textContent = null;
        List<Map<String, Object>> toolCalls = new ArrayList<>();

        for (Object item : list) {
            if (item instanceof Map<?, ?> block) {
                String type = String.valueOf(block.get("type"));
                if ("text".equals(type)) {
                    textContent = String.valueOf(block.get("text"));
                } else if ("tool_use".equals(type)) {
                    // 转换为 OpenAI tool_calls 格式
                    Map<String, Object> toolCall = new LinkedHashMap<>();
                    toolCall.put("id", String.valueOf(block.get("id")));
                    toolCall.put("type", "function");

                    Map<String, Object> function = new LinkedHashMap<>();
                    function.put("name", String.valueOf(block.get("name")));

                    Object input = block.get("input");
                    if (input instanceof Map) {
                        function.put("arguments", JsonUtils.toJson(input));
                    } else if (input instanceof String) {
                        function.put("arguments", input);
                    } else {
                        function.put("arguments", "{}");
                    }
                    toolCall.put("function", function);
                    toolCalls.add(toolCall);
                }
            }
        }

        if (textContent != null) {
            result.put("content", textContent);
        } else if (toolCalls.isEmpty()) {
            // 没有 tool_calls 时必须有 content
            result.put("content", "");
        }

        if (!toolCalls.isEmpty()) {
            result.put("tool_calls", toolCalls);
        }

        return result;
    }

    /**
     * 将 Map 格式的消息转换为 OpenAI 格式。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> convertMapMessage(Map<?, ?> msg) {
        Map<String, Object> result = new LinkedHashMap<>();
        Object role = msg.get("role");
        result.put("role", role != null ? role.toString() : "user");

        Object content = msg.get("content");
        if (content instanceof String text) {
            result.put("content", text);
        } else {
            result.put("content", content);
        }

        return result;
    }

    /**
     * 将 Anthropic 工具定义转换为 OpenAI 格式。
     */
    private List<Map<String, Object>> convertTools(List<?> tools) {
        List<Map<String, Object>> openaiTools = new ArrayList<>();
        for (Object tool : tools) {
            if (tool instanceof ToolSpec spec) {
                Map<String, Object> openaiTool = new LinkedHashMap<>();
                openaiTool.put("type", "function");

                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", spec.name());
                function.put("description", spec.description());
                function.put("parameters", spec.input_schema());
                openaiTool.put("function", function);

                openaiTools.add(openaiTool);
            } else if (tool instanceof Map<?, ?> map) {
                // 尝试从 Map 转换
                Map<String, Object> openaiTool = new LinkedHashMap<>();
                openaiTool.put("type", "function");

                Map<String, Object> function = new LinkedHashMap<>();
                function.put("name", map.get("name"));
                function.put("description", map.get("description"));
                function.put("parameters", map.get("input_schema"));
                openaiTool.put("function", function);

                openaiTools.add(openaiTool);
            }
        }
        return openaiTools;
    }

    @Override
    public String getModelName() {
        return config.getDeepSeekModelId();
    }
}