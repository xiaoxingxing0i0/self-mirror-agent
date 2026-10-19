package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.ConversationAgent;
import com.mirror.agent.agent.resident.ConversationAgent.Scene;
import com.mirror.agent.agent.resident.RetrievalAgent;
import com.mirror.agent.agent.resident.TaskExecutionAgent;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.dp.entity.ConversationContext;
import com.mirror.agent.entity.Task;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.AgentType;
import com.mirror.agent.message.ChatRequest;
import com.mirror.agent.message.LanguageAnalysisResult;
import com.mirror.agent.message.RetrievalItem;
import com.mirror.agent.message.RetrievalRequest;
import com.mirror.agent.message.TagItem;
import com.mirror.agent.service.EventService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 意图路由（分支模式，docs/agent_implementation.md §3.1）：
 * 依据语言分析标签将请求路由到 对话 / 咨询检索 / 历史查询 / 任务创建 分支，
 * 产出对话 Agent 的场景与变量，所有下游调用统一经 AgentInvoker 容错。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IntentRouter {

    private static final Set<String> NEGATIVE_EMOTIONS =
            Set.of("anxious", "down", "angry", "tired", "lonely", "stressed", "sad");

    private static final Map<String, String> TASK_TAG_TO_REPORT_TYPE = Map.of(
            "daily_report", "daily",
            "weekly_report", "weekly",
            "monthly_report", "monthly",
            "custom_report", "custom",
            "statistic", "statistic");

    private final RetrievalAgent retrievalAgent;
    private final TaskExecutionAgent taskExecutionAgent;
    private final AgentInvoker invoker;
    private final EventService eventService;
    private final AgentProperties agentProperties;

    /** 路由结果：对话场景 + 模板变量 + 回复类型 + 关联任务 ID */
    public record RouteOutcome(Scene scene, Map<String, String> vars, String replyType, Long taskId) {
    }

    public RouteOutcome route(Long userId, ConversationContext context,
                              ChatRequest request, LanguageAnalysisResult analysis) {
        String history = context.renderHistory();
        if (analysis.isNeedClarify()) {
            return new RouteOutcome(Scene.CLARIFY, Map.of(
                    "input", request.getContent(),
                    "summary", analysis.getSummary() == null ? "" : analysis.getSummary(),
                    "history", history,
                    "recentEvents", eventContext(userId)), "clarify", null);
        }
        return switch (analysis.getPrimaryIntent()) {
            case "consult" -> consultRoute(userId, request, analysis, history);
            case "query" -> queryRoute(userId, request);
            case "task" -> taskRoute(userId, request, analysis);
            default -> chatRoute(userId, analysis, request, history);
        };
    }

    /** 对话 Prompt 的事件背景注入段（用户近期提及的事件，帮助模型了解前因后果） */
    private String eventContext(Long userId) {
        if (!agentProperties.isEventPipelineEnabled()) {
            return "（无）";
        }
        try {
            String refs = eventService.renderRecentEventRefs(userId);
            return refs.isBlank() ? "（无）" : refs;
        } catch (Exception e) {
            log.warn("事件上下文注入失败，按无事件处理: {}", e.getMessage());
            return "（无）";
        }
    }

    /** 咨询分支：RAG 知识库检索 → 共情回复（scene.md 场景 2） */
    private RouteOutcome consultRoute(Long userId, ChatRequest request,
                                      LanguageAnalysisResult analysis, String history) {
        RetrievalRequest retrievalRequest = RetrievalRequest.builder()
                .userId(userId)
                .query(request.getContent() + " " + safe(analysis.getSummary()))
                .scope(RetrievalRequest.Scope.KNOWLEDGE)
                .build();
        AgentResult<List<RetrievalItem>> result = invoker.invoke(AgentType.RETRIEVAL,
                "retrieve_knowledge", () -> retrievalAgent.retrieve(retrievalRequest));
        String knowledgeContext = result.isSuccess() && result.getData() != null
                ? renderRetrieval(result.getData())
                : "（知识库检索失败，请基于共情对话回应，并说明信息有限）";
        Map<String, String> vars = new HashMap<>();
        vars.put("emotionTags", emotionTagText(analysis));
        vars.put("retrievalContext", knowledgeContext);
        vars.put("history", history);
        vars.put("input", request.getContent());
        vars.put("recentEvents", eventContext(userId));
        return new RouteOutcome(Scene.COMFORT, vars, "consult", null);
    }

    /** 历史查询分支：个人对话数据检索 → 结果告知 */
    private RouteOutcome queryRoute(Long userId, ChatRequest request) {
        RetrievalRequest retrievalRequest = RetrievalRequest.builder()
                .userId(userId)
                .query(request.getContent())
                .scope(RetrievalRequest.Scope.HISTORY)
                .build();
        AgentResult<List<RetrievalItem>> result = invoker.invoke(AgentType.RETRIEVAL,
                "retrieve_history", () -> retrievalAgent.retrieve(retrievalRequest));
        String info = result.isSuccess() && result.getData() != null && !result.getData().isEmpty()
                ? "围绕「" + request.getContent() + "」检索到 " + result.getData().size()
                        + " 条历史对话：\n" + renderRetrieval(result.getData())
                : "未检索到与「" + request.getContent() + "」相关的历史对话记录，请引导用户补充时间或主题。";
        return new RouteOutcome(Scene.RESULT_NOTIFY,
                Map.of("resultInfo", info), "query", null);
    }

    /** 任务分支：创建报告/统计任务 → 任务通知（scene.md 场景 3） */
    private RouteOutcome taskRoute(Long userId, ChatRequest request, LanguageAnalysisResult analysis) {
        String tagCode = analysis.firstTagCode(TagItem.CATEGORY_TASK);
        String reportType = TASK_TAG_TO_REPORT_TYPE.getOrDefault(tagCode, "weekly");
        LocalDate end = LocalDate.now();
        LocalDate start = switch (reportType) {
            case "daily" -> end;
            case "monthly" -> end.minusDays(29);
            default -> end.minusDays(6);
        };
        String taskType = "statistic".equals(reportType) ? Task.TYPE_STATISTIC : Task.TYPE_REPORT;
        Map<String, Object> params = Map.of(
                "reportType", reportType,
                "startDate", start.toString(),
                "endDate", end.toString());
        AgentResult<Task> result = invoker.invoke(AgentType.TASK_EXECUTION, "create_task",
                () -> taskExecutionAgent.createTask(userId, taskType,
                        "生成" + reportTypeName(reportType), params, null));
        String taskInfo = result.isSuccess() && result.getData() != null
                ? "任务已创建并开始执行：名称=" + result.getData().getTaskName()
                        + "，统计周期=" + start + " 至 " + end
                        + "，任务ID=" + result.getData().getId() + "，完成后会向您推送结果。"
                : "任务创建失败：" + result.getErrorMessage() + "，请安抚用户并建议稍后重试。";
        Long taskId = result.isSuccess() && result.getData() != null ? result.getData().getId() : null;
        return new RouteOutcome(Scene.TASK_NOTIFY,
                Map.of("taskInfo", taskInfo, "input", request.getContent()), "task_notify", taskId);
    }

    /** 闲聊/情绪分支：负面情绪走安抚模板，否则日常闲聊模板（串行模式） */
    private RouteOutcome chatRoute(Long userId, LanguageAnalysisResult analysis,
                                   ChatRequest request, String history) {
        boolean negative = analysis.tagsOf(TagItem.CATEGORY_EMOTION).stream()
                .anyMatch(tag -> NEGATIVE_EMOTIONS.contains(tag.getCode()));
        Map<String, String> vars = new HashMap<>();
        vars.put("emotionTags", emotionTagText(analysis));
        vars.put("history", history);
        vars.put("input", request.getContent());
        vars.put("recentEvents", eventContext(userId));
        if (negative) {
            vars.put("retrievalContext", "（本次未检索知识库）");
            return new RouteOutcome(Scene.COMFORT, vars, "chat", null);
        }
        return new RouteOutcome(Scene.DAILY, vars, "chat", null);
    }

    private String renderRetrieval(List<RetrievalItem> items) {
        if (items.isEmpty()) {
            return "（无匹配内容）";
        }
        return items.stream()
                .map(item -> "- [来源:" + item.getSource() + " 相关度:"
                        + String.format("%.2f", item.getScore()) + "] " + item.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String emotionTagText(LanguageAnalysisResult analysis) {
        String text = analysis.tagsOf(TagItem.CATEGORY_EMOTION).stream()
                .map(tag -> tag.getName() + "(" + String.format("%.2f", tag.getConfidence()) + ")")
                .collect(Collectors.joining("、"));
        return text.isEmpty() ? "（无明显情绪倾向）" : text;
    }

    private String reportTypeName(String reportType) {
        return switch (reportType) {
            case "daily" -> "日报";
            case "monthly" -> "月报";
            case "statistic" -> "数据统计";
            case "custom" -> "自定义报告";
            default -> "周报";
        };
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
