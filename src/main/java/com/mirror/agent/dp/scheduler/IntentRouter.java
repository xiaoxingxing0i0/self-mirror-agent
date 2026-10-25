package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.ConversationAgent;
import com.mirror.agent.agent.resident.ConversationAgent.Scene;
import com.mirror.agent.agent.resident.RetrievalAgent;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.dp.entity.ConversationContext;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.AgentType;
import com.mirror.agent.message.ChatRequest;
import com.mirror.agent.message.LanguageAnalysisResult;
import com.mirror.agent.message.PendingPeriod;
import com.mirror.agent.message.RetrievalItem;
import com.mirror.agent.message.RetrievalRequest;
import com.mirror.agent.message.TagItem;
import com.mirror.agent.service.IncidentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
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
    private final AgentInvoker invoker;
    private final IncidentService incidentService;
    private final AgentProperties agentProperties;
    private final ConversationContextManager contextManager;
    private final ReportPeriodResolver periodResolver;
    private final ReportTaskLauncher reportTaskLauncher;

    /** 路由结果：对话场景 + 模板变量 + 回复类型 + 关联任务 ID */
    public record RouteOutcome(Scene scene, Map<String, String> vars, String replyType, Long taskId) {
    }

    public RouteOutcome route(Long userId, ConversationContext context,
                              ChatRequest request, LanguageAnalysisResult analysis) {
        String history = context.renderHistory();
        RouteOutcome fromSlot = pendingPeriodGuard(userId, request, analysis);
        if (fromSlot != null) {
            return fromSlot;
        }
        if (analysis.isNeedClarify()) {
            return new RouteOutcome(Scene.CLARIFY, Map.of(
                    "input", request.getContent(),
                    "summary", analysis.getSummary() == null ? "" : analysis.getSummary(),
                    "history", history,
                    "recentIncidents", incidentContext(userId)), "clarify", null);
        }
        return switch (analysis.getPrimaryIntent()) {
            case "consult" -> consultRoute(userId, request, analysis, history);
            case "query" -> queryRoute(userId, request, analysis);
            case "task" -> taskRoute(userId, request, analysis);
            default -> chatRoute(userId, analysis, request, history);
        };
    }

    /** 对话 Prompt 的事由背景注入段（用户近期提及的事由，帮助模型了解前因后果） */
    private String incidentContext(Long userId) {
        if (!agentProperties.isIncidentPipelineEnabled()) {
            return "（无）";
        }
        try {
            String refs = incidentService.renderRecentIncidentRefs(userId);
            return refs.isBlank() ? "（无）" : refs;
        } catch (Exception e) {
            log.warn("事由上下文注入失败，按无事由处理: {}", e.getMessage());
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
        vars.put("recentIncidents", incidentContext(userId));
        return new RouteOutcome(Scene.COMFORT, vars, "consult", null);
    }

    /**
     * 历史查询分支：模型指认的已知事由（queryIncidentIds，归属复验后）→ 事由档案直检（含时间线
     * 与引用关系网）；叠加个人对话向量检索兜底（列表外更早事由/未指认场景）。
     */
    private RouteOutcome queryRoute(Long userId, ChatRequest request, LanguageAnalysisResult analysis) {
        String dossier = incidentDossier(userId, analysis);
        RetrievalRequest retrievalRequest = RetrievalRequest.builder()
                .userId(userId)
                .query(request.getContent())
                .scope(RetrievalRequest.Scope.HISTORY)
                .build();
        AgentResult<List<RetrievalItem>> result = invoker.invoke(AgentType.RETRIEVAL,
                "retrieve_history", () -> retrievalAgent.retrieve(retrievalRequest));
        StringBuilder info = new StringBuilder();
        if (!dossier.isBlank()) {
            info.append("【事由档案检索结果（结构化档案，优先采信）】\n").append(dossier).append('\n');
        }
        if (result.isSuccess() && result.getData() != null && !result.getData().isEmpty()) {
            info.append("【历史对话记录检索】\n围绕「").append(request.getContent())
                    .append("」检索到 ").append(result.getData().size())
                    .append(" 条历史对话：\n").append(renderRetrieval(result.getData()));
        } else if (dossier.isBlank()) {
            info.append("未检索到与「").append(request.getContent())
                    .append("」相关的历史对话记录，请引导用户补充时间或主题。");
        }
        return new RouteOutcome(Scene.RESULT_NOTIFY,
                Map.of("resultInfo", info.toString()), "query", null);
    }

    /** 事由档案直检：queryIncidentIds 为空/链路关闭/查询异常 → 空串（回落消息向量检索） */
    private String incidentDossier(Long userId, LanguageAnalysisResult analysis) {
        if (!agentProperties.isIncidentPipelineEnabled() || userId == null || analysis == null
                || analysis.getQueryIncidentIds() == null || analysis.getQueryIncidentIds().isEmpty()) {
            return "";
        }
        try {
            return incidentService.renderIncidentDossier(userId, analysis.getQueryIncidentIds());
        } catch (Exception e) {
            log.warn("事由档案检索失败，回落对话记录检索: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 任务分支：创建报告/统计任务 → 任务通知（scene.md 场景 3）。
     * 周期决策：明示锚点（periodHint）优先 → custom/statistic 未明示则挂澄清槽反问一轮
     * （一次对话至多一轮反问，澄清答案即确认，不再叠加独立二次确认轮）→
     * 日报/周报/月报名词自带周期，直接按默认窗口建任务；区间换算与降级见 {@link ReportPeriodResolver}。
     */
    private RouteOutcome taskRoute(Long userId, ChatRequest request, LanguageAnalysisResult analysis) {
        String tagCode = analysis.firstTagCode(TagItem.CATEGORY_TASK);
        String reportType = TASK_TAG_TO_REPORT_TYPE.getOrDefault(tagCode, "weekly");
        LanguageAnalysisResult.PeriodHint hint = analysis.getPeriodHint();
        boolean explicitPeriod = hint != null && Boolean.TRUE.equals(hint.getExplicit());
        boolean periodNeeded = "custom".equals(reportType) || "statistic".equals(reportType);
        if (!explicitPeriod && periodNeeded && agentProperties.isReportPeriodClarifyEnabled()
                && request.getConversationId() != null) {
            contextManager.putPendingPeriod(userId, request.getConversationId(),
                    PendingPeriod.of(userId, request.getConversationId(), reportType));
            return new RouteOutcome(Scene.PERIOD_CLARIFY, Map.of(
                    "reportTypeName", reportTaskLauncher.reportTypeName(reportType),
                    "defaultRange", periodResolver.defaultRangeText(reportType, LocalDate.now()),
                    "input", request.getContent()), "period_clarify", null);
        }
        return reportTaskLauncher.launch(userId, request, reportType,
                explicitPeriod ? hint : null, LocalDate.now());
    }

    /**
     * 周期澄清待办槽守卫（须先于 needClarify 分支执行——「上周」这类短回复会被常规判定误导向澄清）：
     * 槽存在即消费——本轮解析出明示周期→按挂起类型建任务；「随便/都行」→默认周期建任务；
     * 都解析不出（拒绝/换话题）→ 槽已清除，回落正常路由。TTL 到点自动失效。
     */
    private RouteOutcome pendingPeriodGuard(Long userId, ChatRequest request,
                                            LanguageAnalysisResult analysis) {
        Long conversationId = request.getConversationId();
        if (conversationId == null) {
            return null;
        }
        PendingPeriod slot = contextManager.loadPendingPeriod(userId, conversationId);
        if (slot == null) {
            return null;
        }
        contextManager.clearPendingPeriod(userId, conversationId);
        LanguageAnalysisResult.PeriodHint hint = analysis == null ? null : analysis.getPeriodHint();
        boolean hasRange = hint != null && Boolean.TRUE.equals(hint.getExplicit());
        if (!hasRange && !periodResolver.isDefaultAccept(request.getContent())) {
            return null;
        }
        return reportTaskLauncher.launch(userId, request, slot.getReportType(),
                hasRange ? hint : null, LocalDate.now());
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
        vars.put("recentIncidents", incidentContext(userId));
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

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
