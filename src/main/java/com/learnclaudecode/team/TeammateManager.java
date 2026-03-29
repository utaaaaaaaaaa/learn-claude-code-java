package com.learnclaudecode.team;

import com.learnclaudecode.common.JsonUtils;
import com.learnclaudecode.common.LLMClient;
import com.learnclaudecode.common.WorkspacePaths;
import com.learnclaudecode.agents.StageConfig;
import com.learnclaudecode.model.ChatMessage;
import com.learnclaudecode.model.TaskRecord;
import com.learnclaudecode.tasks.TaskManager;
import com.learnclaudecode.tools.CommandTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 队友管理器，统一承载 s09-s11 的持续线程、消息协议与自治认领能力。
 *
 * 这个类对应的是 Claude Code / Agent 系统里非常重要的一步升级：
 * 从“单个 Agent 自己干活”，升级到“多个 Agent 分工协作”。
 *
 * 在这个项目里：
 * - lead 是主代理；
 * - teammate 是被创建出来的长期协作代理；
 * - inbox/message bus 是它们的通信渠道；
 * - task board 是它们共享的工作池。
 *
 * 因此，这个类本质上是在回答：
 * “如果一个 Agent 不够用，怎么把一个复杂任务拆给多个持续运行的 Agent 一起完成？”
 */
public class TeammateManager {
    private final WorkspacePaths paths;
    private final LLMClient client;
    private final CommandTools commandTools;
    private final MessageBus bus;
    private final TaskManager taskManager;
    private final Path configPath;
    private final Map<String, Map<String, Object>> shutdownRequests = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> planRequests = new ConcurrentHashMap<>();

    /**
     * 初始化队友管理器及其依赖。
     *
     * @param paths 工作区路径工具
     * @param client 模型客户端
     * @param commandTools 命令工具
     * @param bus 消息总线
     * @param taskManager 任务管理器
     */
    public TeammateManager(WorkspacePaths paths,
                           LLMClient client,
                           CommandTools commandTools,
                           MessageBus bus,
                           TaskManager taskManager) {
        this.paths = paths;
        this.client = client;
        this.commandTools = commandTools;
        this.bus = bus;
        this.taskManager = taskManager;
        this.configPath = paths.teamDir().resolve("config.json");
        ensureConfig();
    }

    /**
     * 创建并启动一个新的队友线程。
     *
     * @param name 队友名称
     * @param role 队友角色
     * @param prompt 初始任务提示
     * @param autonomous 是否启用自治模式
     * @return 启动结果
     */
    public synchronized String spawn(String name, String role, String prompt, boolean autonomous) {
        Map<String, Object> config = loadConfig();
        List<Map<String, Object>> members = members(config);
        Map<String, Object> existing = findMember(members, name);
        if (existing != null) {
            String status = String.valueOf(existing.getOrDefault("status", "idle"));
            if (!("idle".equals(status) || "shutdown".equals(status))) {
                return "Error: '" + name + "' is currently " + status;
            }
            existing.put("status", "working");
            existing.put("role", role);
        } else {
            Map<String, Object> member = new HashMap<>();
            member.put("name", name);
            member.put("role", role);
            member.put("status", "working");
            members.add(member);
        }
        saveConfig(config);
        // 每个队友都运行在独立守护线程中，主 lead 只负责创建与协调，不直接串行执行队友任务。
        Thread thread = new Thread(() -> loop(name, role, prompt, autonomous));
        thread.setDaemon(true);
        thread.start();
        return "Spawned '" + name + "' (role: " + role + ")";
    }

    /**
     * 列出当前所有队友状态。
     *
     * @return 队友列表文本
     */
    public String listAll() {
        Map<String, Object> config = loadConfig();
        List<Map<String, Object>> members = members(config);
        if (members.isEmpty()) {
            return "No teammates.";
        }
        List<String> lines = new ArrayList<>();
        lines.add("Team: " + config.getOrDefault("team_name", "default"));
        for (Map<String, Object> member : members) {
            lines.add("  " + member.get("name") + " (" + member.get("role") + "): " + member.get("status"));
        }
        return String.join("\n", lines);
    }

    /**
     * 返回所有队友名称。
     *
     * @return 队友名称列表
     */
    public List<String> memberNames() {
        return members(loadConfig()).stream().map(member -> String.valueOf(member.get("name"))).toList();
    }

    /**
     * 向指定队友发起关闭请求。
     *
     * @param teammate 队友名称
     * @return 请求发送结果
     */
    public String handleShutdownRequest(String teammate) {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        // lead 记录 shutdown 请求状态，后续由队友通过 shutdown_response 回传处理结果。
        shutdownRequests.put(requestId, new ConcurrentHashMap<>(Map.of("target", teammate, "status", "pending")));
        bus.send("lead", teammate, "Please shut down.", "shutdown_request", Map.of("request_id", requestId));
        return "Shutdown request " + requestId + " sent to '" + teammate + "'";
    }

    /**
     * 处理队友提交的计划审批请求。
     *
     * @param requestId 请求 ID
     * @param approve 是否批准
     * @param feedback 反馈信息
     * @return 处理结果
     */
    public String handlePlanReview(String requestId, boolean approve, String feedback) {
        Map<String, Object> request = planRequests.get(requestId);
        if (request == null) {
            return "Error: Unknown plan request_id '" + requestId + "'";
        }
        request.put("status", approve ? "approved" : "rejected");
        bus.send("lead", String.valueOf(request.get("from")), feedback == null ? "" : feedback,
                "plan_approval_response",
                Map.of("request_id", requestId, "approve", approve, "feedback", feedback == null ? "" : feedback));
        return "Plan " + request.get("status") + " for '" + request.get("from") + "'";
    }

    /**
     * 运行单个队友的主循环。
     *
     * @param name 队友名称
     * @param role 队友角色
     * @param prompt 初始提示
     * @param autonomous 是否自治
     */
    private void loop(String name, String role, String prompt, boolean autonomous) {
        String teamName = String.valueOf(loadConfig().getOrDefault("team_name", "default"));
        String system = autonomous
                ? "You are '" + name + "', role: " + role + ", team: " + teamName + ", at " + paths.workdir() + ". Use idle when done. You may auto-claim tasks."
                : "You are '" + name + "', role: " + role + ", at " + paths.workdir() + ". Use send_message to communicate. Complete your task.";
        List<ChatMessage> messages = new ArrayList<>();
        List<Map<String, Object>> tools = teammateTools(autonomous);
        messages.add(new ChatMessage("user", prompt));
        while (true) {
            // 队友循环先消费 inbox，再调用模型，和主代理的“消息 -> 推理 -> 工具执行”节奏一致。
            for (Map<String, Object> inboxMessage : bus.readInbox(name)) {
                if ("shutdown_request".equals(inboxMessage.get("type"))) {
                    if (autonomous) {
                        setStatus(name, "shutdown");
                        return;
                    }
                }
                messages.add(new ChatMessage("user", JsonUtils.toJson(inboxMessage)));
            }
            var response = client.createMessage(system, messages, tools, 4000);
            List<Map<String, Object>> content = response.content();
            messages.add(new ChatMessage("assistant", content));
            if (!"tool_use".equals(response.stop_reason())) {
                if (autonomous) {
                    // 自治队友在完成当前任务后不会立刻退出，而是先进入 idle，再尝试自动接单。
                    setStatus(name, "idle");
                    if (!resumeAutonomous(name, role, teamName, messages)) {
                        setStatus(name, "shutdown");
                        return;
                    }
                    setStatus(name, "working");
                    continue;
                }
                setStatus(name, "idle");
                return;
            }
            List<Map<String, Object>> results = new ArrayList<>();
            boolean idleRequested = false;
            for (Map<String, Object> block : content) {
                if (!"tool_use".equals(String.valueOf(block.get("type")))) {
                    continue;
                }
                String toolName = String.valueOf(block.get("name"));
                @SuppressWarnings("unchecked")
                Map<String, Object> input = (Map<String, Object>) block.getOrDefault("input", Map.of());
                String output;
                // 队友工具子集与主代理不同：更强调通信、认领任务和协议响应。
                switch (toolName) {
                    case "bash" -> output = commandTools.runBash(String.valueOf(input.get("command")));
                    case "read_file" -> output = commandTools.runRead(String.valueOf(input.get("path")), null);
                    case "write_file" -> output = commandTools.runWrite(String.valueOf(input.get("path")), String.valueOf(input.get("content")));
                    case "edit_file" -> output = commandTools.runEdit(String.valueOf(input.get("path")), String.valueOf(input.get("old_text")), String.valueOf(input.get("new_text")));
                    case "send_message" -> output = bus.send(name, String.valueOf(input.get("to")), String.valueOf(input.get("content")), String.valueOf(input.getOrDefault("msg_type", "message")), Map.of());
                    case "read_inbox" -> output = JsonUtils.toPrettyJson(bus.readInbox(name));
                    case "claim_task" -> output = taskManager.claim(((Number) input.get("task_id")).intValue(), name);
                    case "idle" -> {
                        output = "Entering idle phase.";
                        idleRequested = true;
                    }
                    case "shutdown_response" -> {
                        String reqId = String.valueOf(input.get("request_id"));
                        boolean approve = Boolean.parseBoolean(String.valueOf(input.get("approve")));
                        Map<String, Object> req = shutdownRequests.get(reqId);
                        if (req != null) {
                            req.put("status", approve ? "approved" : "rejected");
                        }
                        output = bus.send(name, "lead", String.valueOf(input.getOrDefault("reason", "")), "shutdown_response", Map.of("request_id", reqId, "approve", approve));
                        if (approve) {
                            setStatus(name, "shutdown");
                            return;
                        }
                    }
                    case "plan_approval" -> {
                        String reqId = UUID.randomUUID().toString().substring(0, 8);
                        String plan = String.valueOf(input.getOrDefault("plan", ""));
                        planRequests.put(reqId, new ConcurrentHashMap<>(Map.of("from", name, "plan", plan, "status", "pending")));
                        output = bus.send(name, "lead", plan, "plan_approval_response", Map.of("request_id", reqId, "plan", plan));
                    }
                    default -> output = "Unknown tool: " + toolName;
                }
                results.add(Map.of(
                        "type", "tool_result",
                        "tool_use_id", String.valueOf(block.get("id")),
                        "content", output
                ));
            }
            messages.add(new ChatMessage("user", results));
            if (autonomous && idleRequested) {
                setStatus(name, "idle");
                if (!resumeAutonomous(name, role, teamName, messages)) {
                    setStatus(name, "shutdown");
                    return;
                }
                setStatus(name, "working");
            }
        }
    }

    /**
     * 根据模式构造队友可用的工具列表。
     *
     * @param autonomous 是否自治
     * @return 工具定义列表
     */
    private List<Map<String, Object>> teammateTools(boolean autonomous) {
        // 队友工具以 baseTools 为底座，再按是否自治补齐消息、协议和任务相关能力。
        List<Map<String, Object>> tools = new ArrayList<>(StageConfig.baseTools());
        tools.add(tool("send_message", "Send message to a teammate.", Map.of("type", "object", "properties", Map.of("to", Map.of("type", "string"), "content", Map.of("type", "string"), "msg_type", Map.of("type", "string")), "required", List.of("to", "content"))));
        tools.add(tool("read_inbox", "Read and drain your inbox.", Map.of("type", "object", "properties", Map.of())));
        if (autonomous) {
            tools.add(tool("claim_task", "Claim a task by ID.", Map.of("type", "object", "properties", Map.of("task_id", Map.of("type", "integer")), "required", List.of("task_id"))));
            tools.add(tool("idle", "Signal no more work for now.", Map.of("type", "object", "properties", Map.of())));
        } else {
            tools.add(tool("shutdown_response", "Respond to a shutdown request.", Map.of("type", "object", "properties", Map.of("request_id", Map.of("type", "string"), "approve", Map.of("type", "boolean"), "reason", Map.of("type", "string")), "required", List.of("request_id", "approve"))));
            tools.add(tool("plan_approval", "Submit a plan for lead approval.", Map.of("type", "object", "properties", Map.of("plan", Map.of("type", "string")), "required", List.of("plan"))));
        }
        return tools;
    }

    /**
     * 构造单个队友工具定义。
     *
     * @param name 工具名
     * @param description 工具描述
     * @param schema 输入 schema
     * @return 工具定义
     */
    private Map<String, Object> tool(String name, String description, Map<String, Object> schema) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("input_schema", schema);
        return tool;
    }

    /**
     * 在自治模式下尝试让队友从 idle 恢复工作。
     *
     * @param name 队友名称
     * @param role 队友角色
     * @param teamName 团队名
     * @param messages 当前消息上下文
     * @return 成功恢复工作时返回 true
     */
    private boolean resumeAutonomous(String name, String role, String teamName, List<ChatMessage> messages) {
        int attempts = 60 / 5;
        for (int i = 0; i < attempts; i++) {
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
            List<Map<String, Object>> inbox = bus.readInbox(name);
            if (!inbox.isEmpty()) {
                // 空闲阶段如果收到新消息，优先恢复消息驱动工作，而不是盲目抢任务。
                for (Map<String, Object> msg : inbox) {
                    if ("shutdown_request".equals(msg.get("type"))) {
                        return false;
                    }
                    messages.add(new ChatMessage("user", JsonUtils.toJson(msg)));
                }
                return true;
            }
            List<TaskRecord> unclaimed = taskManager.scanUnclaimed();
            if (!unclaimed.isEmpty()) {
                // 若没有新消息，则尝试自动认领一个未阻塞任务，模拟 s11 的自治行为。
                TaskRecord task = unclaimed.get(0);
                taskManager.claim(task.id, name);
                if (messages.size() <= 3) {
                    messages.add(0, new ChatMessage("user", "<identity>You are '" + name + "', role: " + role + ", team: " + teamName + ".</identity>"));
                    messages.add(1, new ChatMessage("assistant", "I am " + name + ". Continuing."));
                }
                messages.add(new ChatMessage("user", "<auto-claimed>Task #" + task.id + ": " + task.subject + "\n" + task.description + "</auto-claimed>"));
                messages.add(new ChatMessage("assistant", "Claimed task #" + task.id + ". Working on it."));
                return true;
            }
        }
        return false;
    }

    /**
     * 确保团队配置文件存在。
     */
    private void ensureConfig() {
        if (!Files.exists(configPath)) {
            saveConfig(new HashMap<>(Map.of("team_name", "default", "members", new ArrayList<>())));
        }
    }

    /**
     * 读取团队配置文件。
     *
     * @return 团队配置映射
     */
    private synchronized Map<String, Object> loadConfig() {
        try {
            ensureConfig();
            return JsonUtils.fromJson(Files.readString(configPath, StandardCharsets.UTF_8), Map.class);
        } catch (IOException e) {
            throw new IllegalStateException("读取 team 配置失败", e);
        }
    }

    /**
     * 保存团队配置文件。
     *
     * @param config 配置内容
     */
    private synchronized void saveConfig(Map<String, Object> config) {
        try {
            Files.createDirectories(paths.teamDir());
            Files.writeString(configPath, JsonUtils.toPrettyJson(config), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("保存 team 配置失败", e);
        }
    }

    /**
     * 获取团队配置中的成员列表。
     *
     * @param config 团队配置
     * @return 成员列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> members(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.computeIfAbsent("members", key -> new ArrayList<>());
    }

    /**
     * 按名称查找队友配置项。
     *
     * @param members 成员列表
     * @param name 队友名称
     * @return 成员配置，未找到则返回 null
     */
    private Map<String, Object> findMember(List<Map<String, Object>> members, String name) {
        for (Map<String, Object> member : members) {
            if (name.equals(member.get("name"))) {
                return member;
            }
        }
        return null;
    }

    /**
     * 更新队友状态。
     *
     * @param name 队友名称
     * @param status 队友状态
     */
    private synchronized void setStatus(String name, String status) {
        Map<String, Object> config = loadConfig();
        Map<String, Object> member = findMember(members(config), name);
        if (member != null) {
            member.put("status", status);
            member.put("updated_at", Instant.now().toEpochMilli());
            saveConfig(config);
        }
    }
}
