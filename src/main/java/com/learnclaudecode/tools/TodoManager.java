package com.learnclaudecode.tools;

import com.learnclaudecode.model.TodoItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Todo 管理器，支持 s03 与 s_full 中的不同字段风格。
 */
public class TodoManager {
    private List<Map<String, Object>> items = new ArrayList<>();

    /**
     * 校验并更新当前 Todo 列表。
     *
     * @param newItems 新的 Todo 项列表
     * @return 渲染后的 Todo 文本
     */
    public String update(List<Map<String, Object>> newItems) {
        if (newItems.size() > 20) {
            throw new IllegalArgumentException("Max 20 todos allowed");
        }
        int inProgressCount = 0;
        List<Map<String, Object>> validated = new ArrayList<>();
        for (int i = 0; i < newItems.size(); i++) {
            Map<String, Object> item = newItems.get(i);
            // 兼容多种 status 值：todo -> pending, doing -> in_progress, done -> completed
            String rawStatus = String.valueOf(item.getOrDefault("status", "pending")).toLowerCase();
            String status = switch (rawStatus) {
                case "todo", "pending" -> "pending";
                case "doing", "in_progress" -> "in_progress";
                case "done", "completed" -> "completed";
                default -> rawStatus;
            };
            // 兼容多种字段名：text, content, name
            String text = String.valueOf(item.getOrDefault("text",
                    item.getOrDefault("content",
                            item.getOrDefault("name", "")))).trim();
            String id = String.valueOf(item.getOrDefault("id", i + 1));
            String activeForm = String.valueOf(item.getOrDefault("activeForm", text)).trim();
            if (text.isBlank()) {
                throw new IllegalArgumentException("Todo text/content required");
            }
            if (!List.of("pending", "in_progress", "completed").contains(status)) {
                throw new IllegalArgumentException("Invalid status: " + status);
            }
            if ("in_progress".equals(status)) {
                inProgressCount++;
            }
            validated.add(Map.of(
                    "id", id,
                    "text", text,
                    "status", status,
                    "activeForm", activeForm,
                    "content", text
            ));
        }
        if (inProgressCount > 1) {
            throw new IllegalArgumentException("Only one task can be in_progress at a time");
        }
        items = validated;
        return render();
    }

    /**
     * 判断是否仍有未完成的 Todo。
     *
     * @return 存在未完成项时返回 true
     */
    public boolean hasOpenItems() {
        return items.stream().anyMatch(item -> !"completed".equals(item.get("status")));
    }

    /**
     * 将 Todo 列表渲染为文本看板。
     *
     * @return Todo 文本表示
     */
    public String render() {
        if (items.isEmpty()) {
            return "No todos.";
        }
        List<String> lines = new ArrayList<>();
        int done = 0;
        for (Map<String, Object> item : items) {
            String status = String.valueOf(item.get("status"));
            String marker = switch (status) {
                case "completed" -> "[x]";
                case "in_progress" -> "[>]";
                default -> "[ ]";
            };
            if ("completed".equals(status)) {
                done++;
            }
            lines.add(marker + " #" + item.get("id") + ": " + item.get("text"));
        }
        lines.add("\n(" + done + "/" + items.size() + " completed)");
        return String.join("\n", lines);
    }
}
