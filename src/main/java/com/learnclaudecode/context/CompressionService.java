package com.learnclaudecode.context;

import com.learnclaudecode.common.JsonUtils;
import com.learnclaudecode.common.LLMClient;
import com.learnclaudecode.common.WorkspacePaths;
import com.learnclaudecode.model.ChatMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 上下文压缩服务，对齐 s06 的三层压缩思想。
 *
 * 这个类要解决的是 Agent 项目里一个非常现实的问题：
 * 对话越长，发给模型的历史就越多，最终一定会碰到上下文窗口上限。
 *
 * Claude Code 风格的解决思路不是“简单删掉旧消息”，而是分层处理：
 * 1. 先做 micro compact：清理旧工具结果中的大文本；
 * 2. 如果还不够，再做 auto compact：把完整对话转存，再生成摘要；
 * 3. 用“摘要 + 确认消息”替换旧历史，让 Agent 能继续干活。
 */
public class CompressionService {
    private final WorkspacePaths paths;
    private final LLMClient client;
    private final int threshold;
    private final int keepRecent;

    /**
     * 初始化上下文压缩服务。
     *
     * @param paths 工作区路径工具
     * @param client 模型客户端
     * @param threshold 触发自动压缩的阈值
     * @param keepRecent micro compact 时保留的最近结果数
     */
    public CompressionService(WorkspacePaths paths, LLMClient client, int threshold, int keepRecent) {
        this.paths = paths;
        this.client = client;
        this.threshold = threshold;
        this.keepRecent = keepRecent;
    }

    /**
     * 粗略估算当前消息历史的 token 数。
     *
     * @param messages 消息历史
     * @return 估算 token 数
     */
    public int estimateTokens(List<ChatMessage> messages) {
        // 这里没有做精确 tokenizer，而是用 JSON 长度 / 4 做近似估算。
        // 对教学项目来说，这种轻量估算已经足够判断“是否快超窗”。
        return JsonUtils.toJson(messages).length() / 4;
    }

    /**
     * 对较老的工具结果做轻量清理。
     *
     * @param messages 消息历史
     */
    public void microCompact(List<ChatMessage> messages) {
        // 微压缩只清理较老的 tool_result 大文本，尽量保留最近几轮完整上下文。
        List<Map<String, Object>> toolResults = new ArrayList<>();
        for (ChatMessage message : messages) {
            if (!"user".equals(message.role()) || !(message.content() instanceof List<?> parts)) {
                continue;
            }
            for (Object part : parts) {
                if (part instanceof Map<?, ?> raw) {
                    Object type = raw.get("type");
                    if ("tool_result".equals(type)) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) part;
                        toolResults.add(typed);
                    }
                }
            }
        }
        if (toolResults.size() <= keepRecent) {
            return;
        }
        for (int i = 0; i < toolResults.size() - keepRecent; i++) {
            Map<String, Object> result = toolResults.get(i);
            Object content = result.get("content");
            if (content instanceof String text && text.length() > 100) {
                result.put("content", "[cleared]");
            }
        }
    }

    /**
     * 将完整历史转存并生成摘要上下文。
     *
     * @param messages 原始消息历史
     * @return 压缩后的消息历史
     */
    public List<ChatMessage> autoCompact(List<ChatMessage> messages) {
        try {
            Files.createDirectories(paths.transcriptDir());
            // 真实长对话先落盘为 transcript，再把摘要注回上下文，便于后续追溯。
            Path transcript = paths.transcriptDir().resolve("transcript_" + Instant.now().getEpochSecond() + ".jsonl");
            List<String> lines = new ArrayList<>();
            for (ChatMessage message : messages) {
                lines.add(JsonUtils.toJson(message));
            }
            Files.write(transcript, lines, StandardCharsets.UTF_8);
            String conversation = JsonUtils.toJson(messages);
            String prompt = "Summarize this conversation for continuity. Include what was accomplished, current state, and key decisions.\n\n"
                    + conversation.substring(0, Math.min(80000, conversation.length()));
            // 摘要本身也交给模型生成，这样能尽量保留任务状态而不是机械截断历史。
            String summary = client.createMessage(null, List.of(Map.of("role", "user", "content", prompt)), List.of(), 2000)
                    .content()
                    .stream()
                    .filter(block -> block.containsKey("text"))
                    .map(block -> String.valueOf(block.get("text")))
                    .findFirst()
                    .orElse("(summary unavailable)");
            List<ChatMessage> compacted = new ArrayList<>();
            compacted.add(new ChatMessage("user", "[Conversation compressed. Transcript: " + transcript + "]\n\n" + summary));
            compacted.add(new ChatMessage("assistant", "Understood. I have the context from the summary. Continuing."));
            return compacted;
        } catch (IOException e) {
            throw new IllegalStateException("压缩会话失败", e);
        }
    }

    /**
     * 判断当前消息历史是否需要自动压缩。
     *
     * @param messages 消息历史
     * @return 超过阈值时返回 true
     */
    public boolean needsAutoCompact(List<ChatMessage> messages) {
        return estimateTokens(messages) > threshold;
    }
}
