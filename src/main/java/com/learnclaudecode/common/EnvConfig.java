package com.learnclaudecode.common;

import io.github.cdimascio.dotenv.Dotenv;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * 环境配置读取器
 */
public final class EnvConfig {
    private final Dotenv dotenv;
    private final String modelId;
    private final String apiKey;
    private final String baseUrl;
    private final Path workdir;

    // DeepSeek 配置
    private final String deepSeekApiKey;
    private final String deepSeekBaseUrl;
    private final String deepSeekModelId;

    // 客户端选择
    private final String llmProvider;

    /**
     * 读取环境变量并初始化模型访问所需配置。
     */
    public EnvConfig() {
        // 优先允许从 .env 读取，同时兼容系统环境变量覆盖。
        this.dotenv = Dotenv.configure().ignoreIfMissing().load();
        this.modelId = getenv("MODEL_ID").orElse("");
        this.apiKey = getenv("ANTHROPIC_API_KEY").orElse("");
        this.baseUrl = getenv("ANTHROPIC_BASE_URL").orElse("https://api.anthropic.com");
        this.workdir = Paths.get("").toAbsolutePath().normalize();

        // DeepSeek 配置读取
        this.deepSeekApiKey = getenv("DEEPSEEK_API_KEY").orElse("");
        this.deepSeekBaseUrl = getenv("DEEPSEEK_BASE_URL").orElse("https://api.deepseek.com");
        this.deepSeekModelId = getenv("DEEPSEEK_MODEL_ID").orElse("deepseek-chat");

        // 客户端选择：默认 anthropic，可设置为 deepseek
        this.llmProvider = getenv("LLM_PROVIDER").orElse("anthropic");
    }

    /**
     * 读取必填环境变量。
     *
     * @param key 环境变量名
     * @return 环境变量值
     */
    private String require(String key) {
        return getenv(key).orElseThrow(() -> new IllegalStateException("缺少环境变量: " + key));
    }

    /**
     * 读取环境变量，优先使用系统环境变量，其次读取 .env。
     *
     * @param key 环境变量名
     * @return 环境变量值
     */
    public Optional<String> getenv(String key) {
        // 系统环境变量优先级高于 .env，便于在 CI 或 IDEA 运行配置里覆盖本地值。
        String envValue = System.getenv(key);
        if (envValue != null && !envValue.isBlank()) {
            return Optional.of(envValue);
        }
        String dotValue = dotenv.get(key);
        if (dotValue != null && !dotValue.isBlank()) {
            return Optional.of(dotValue);
        }
        return Optional.empty();
    }

    /**
     * 返回当前使用的模型 ID。
     *
     * @return 模型 ID
     */
    public String getModelId() {
        return modelId;
    }

    /**
     * 返回当前使用的 API Key。
     *
     * @return API Key
     */
    public String getApiKey() {
        return apiKey;
    }

    /**
     * 返回 Anthropic-compatible 服务的基础地址。
     *
     * @return 基础 URL
     */
    public String getBaseUrl() {
        return baseUrl;
    }

    /**
     * 返回当前工作目录。
     *
     * @return 工作目录路径
     */
    public Path getWorkdir() {
        return workdir;
    }

    /**
     * 返回 DeepSeek API Key。
     *
     * @return DeepSeek API Key
     */
    public String getDeepSeekApiKey() {
        return deepSeekApiKey;
    }

    /**
     * 返回 DeepSeek 服务的基础地址。
     *
     * @return DeepSeek Base URL
     */
    public String getDeepSeekBaseUrl() {
        return deepSeekBaseUrl;
    }

    /**
     * 返回 DeepSeek 模型 ID。
     *
     * @return DeepSeek 模型 ID
     */
    public String getDeepSeekModelId() {
        return deepSeekModelId;
    }

    /**
     * 返回 LLM 提供者选择。
     *
     * @return 提供者名称（anthropic 或 deepseek）
     */
    public String getLlmProvider() {
        return llmProvider;
    }

    /**
     * 判断是否使用 DeepSeek 客户端。
     *
     * @return 是否使用 DeepSeek
     */
    public boolean useDeepSeek() {
        return "deepseek".equalsIgnoreCase(llmProvider);
    }
}