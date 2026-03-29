package com.learnclaudecode.common;

import com.learnclaudecode.model.AnthropicResponse;

import java.util.List;

/**
 * 大模型客户端抽象接口。
 *
 * 无论是 Anthropic Claude、DeepSeek 还是其他 OpenAI-compatible 服务，
 * 都通过这个统一接口与上层 AgentRuntime 交互。
 *
 * 这样设计的好处是：
 * 1. 上层代码（AgentRuntime、CompressionService、TeammateManager）无需关心底层模型厂商；
 * 2. 可以通过配置灵活切换模型服务，便于测试、成本控制或多模型对比；
 * 3. 新增模型支持只需实现本接口，无需改动其他代码。
 */
public interface LLMClient {

    /**
     * 调用大模型生成下一轮响应。
     *
     * @param system    system prompt
     * @param messages  对话历史
     * @param tools     可用工具定义
     * @param maxTokens 最大输出 token 数
     * @return 模型响应（统一使用 Anthropic 格式）
     */
    AnthropicResponse createMessage(String system, List<?> messages, List<?> tools, int maxTokens);

    /**
     * 返回当前使用的模型名称（用于日志和调试）。
     *
     * @return 模型名称
     */
    String getModelName();
}