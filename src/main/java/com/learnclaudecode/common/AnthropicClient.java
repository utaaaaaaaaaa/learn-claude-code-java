package com.learnclaudecode.common;

import com.learnclaudecode.model.AnthropicResponse;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 轻量级 Anthropic Messages API 调用器。
 *
 * 对刚接触大模型开发的读者来说，可以把这个类理解为”模型网关”：
 * - AgentRuntime 负责决定”下一步要不要调模型”；
 * - StageConfig 负责决定”这一轮允许模型使用哪些工具”；
 * - 本类负责把这些信息拼成 HTTP 请求，真正发给模型服务端。
 *
 * 也就是说，Agent 并不是直接”调用一个 Java 方法就得到智能结果”，
 * 而是把当前对话历史、system prompt、tool 定义一起发给模型，
 * 再由模型返回文本回答或 tool_use 指令。
 */
public class AnthropicClient implements LLMClient {
    private final EnvConfig config;
    private final HttpClient httpClient;

    /**
     * 根据环境配置初始化模型客户端。
     *
     * @param config 环境配置
     */
    public AnthropicClient(EnvConfig config) {
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /**
     * 调用 Anthropic-compatible messages API 生成下一轮响应。
     *
     * @param system system prompt
     * @param messages 对话历史
     * @param tools 可用工具定义
     * @param maxTokens 最大输出 token 数
     * @return 模型响应
     */
    public AnthropicResponse createMessage(String system, List<?> messages, List<?> tools, int maxTokens) {
        // 请求体字段尽量对齐 Anthropic messages API，便于兼容 Anthropic-compatible 提供方。
        // 这里的 payload 就是一次“大模型推理请求”的完整输入：
        // 1. model: 用哪个模型；
        // 2. messages: 当前会话历史；
        // 3. system: 系统级约束；
        // 4. tools: 当前允许模型调用的工具；
        // 5. max_tokens: 允许模型本轮最多输出多少 token。
        Map<String, Object> payload = new HashMap<>();
        payload.put("model", config.getModelId());
        payload.put("max_tokens", maxTokens);
        payload.put("messages", messages);
        if (system != null && !system.isBlank()) {
            payload.put("system", system);
        }
        if (tools != null && !tools.isEmpty()) {
            payload.put("tools", tools);
        }

        // Base URL 允许来自 .env，方便接入智谱、OpenRouter 等兼容端点。
        // 这也是 Claude Code 类项目很常见的设计：
        // “上层只关心 Anthropic 风格协议，下层可以替换成任意兼容实现”。
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(config.getBaseUrl().replaceAll("/$", "") + "/v1/messages"))
                .timeout(Duration.ofSeconds(180))
                .header("content-type", "application/json")
                .header("anthropic-version", "2023-06-01")
                .POST(HttpRequest.BodyPublishers.ofString(JsonUtils.toJson(payload)));

        if (!config.getApiKey().isBlank()) {
            builder.header("x-api-key", config.getApiKey());
        }

        try {
            // 这里真正发生网络调用。前面所有 Agent 行为，本质上都是为了准备这次请求。
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                // 直接把响应体抛出来，方便调试兼容接口返回的错误详情。
                throw new IllegalStateException("Anthropic API 调用失败: HTTP " + response.statusCode() + "\n" + response.body());
            }
            // 解析后的结果会交回 AgentRuntime，由它判断本轮是文本回复还是 tool_use。
            return JsonUtils.fromJson(response.body(), AnthropicResponse.class);
        } catch (IOException | InterruptedException e) {
            // 中断时也统一转成业务异常，避免上层每处都重复处理网络细节。
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Anthropic API 调用失败", e);
        }
    }

    @Override
    public String getModelName() {
        return config.getModelId();
    }
}
