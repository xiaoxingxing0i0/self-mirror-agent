package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.ComplianceAgent;
import com.mirror.agent.agent.resident.ConversationAgent;
import com.mirror.agent.agent.resident.LanguageAnalysisAgent;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.dp.entity.ConversationContext;
import com.mirror.agent.dp.scheduler.IntentRouter.RouteOutcome;
import com.mirror.agent.entity.Conversation;
import com.mirror.agent.entity.Message;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.AgentType;
import com.mirror.agent.message.ChatResponse;
import com.mirror.agent.message.ComplianceResult;
import com.mirror.agent.message.ComplianceResult.TextType;
import com.mirror.agent.message.ComplianceResult.ViolationLevel;
import com.mirror.agent.message.LanguageAnalysisResult;
import com.mirror.agent.message.TagItem;
import com.mirror.agent.service.ConversationService;
import com.mirror.agent.service.EventService;
import com.mirror.agent.service.TagService;
import com.mirror.agent.service.VectorArchiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 对话链路原子步骤门面：输入/输出合规、语言分析（含降级）、生成+合规重生成、消息归档、上下文回写。
 * 由 DpScheduleAgent（标准链路）与 AggregationCoordinator（碎片合并链路）共用，
 * 所有模型调用统一经 AgentInvoker 重试/熔断门面（docs/agent_implementation.md §3.1）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatChainSupport {

    private final AgentInvoker invoker;
    private final ComplianceAgent complianceAgent;
    private final LanguageAnalysisAgent languageAnalysisAgent;
    private final ConversationAgent conversationAgent;
    private final ConversationContextManager contextManager;
    private final ConversationService conversationService;
    private final TagService tagService;
    private final VectorArchiveService vectorArchiveService;
    private final EventService eventService;
    private final AgentProperties agentProperties;

    /** 输入合规检查 */
    public ComplianceResult checkInput(String text) {
        return invokeCompliance(text, TextType.INPUT);
    }

    /** 输出合规检查 */
    public ComplianceResult checkOutput(String text) {
        return invokeCompliance(text, TextType.OUTPUT);
    }

    /** 合规不通过时的兜底话术 */
    public String fallbackReply(ComplianceResult checkResult) {
        return checkResult.getInterceptedReply() != null
                ? checkResult.getInterceptedReply() : ComplianceAgent.MODERATE_FALLBACK;
    }

    /** 语言分析 + 事件抽取（失败时由 Agent 内部降级为待澄清闲聊；熔断时调度层兜底） */
    public LanguageAnalysisResult analyze(String content, ConversationContext context) {
        String eventRefs = renderRecentEventRefs(context.getUserId());
        AgentResult<LanguageAnalysisResult> result = invoker.invoke(AgentType.LANGUAGE_ANALYSIS,
                "analyze", () -> languageAnalysisAgent.analyze(
                        content, context.renderHistory(), eventRefs));
        if (result.getData() != null) {
            return result.getData();
        }
        log.warn("语言分析不可用（熔断降级），按待澄清闲聊处理");
        LanguageAnalysisResult fallback = new LanguageAnalysisResult();
        fallback.setPrimaryIntent("chat");
        fallback.setNeedClarify(true);
        fallback.setSummary("语言分析暂不可用");
        return fallback;
    }

    /** 最近提及事件注入（场景 2 上下文指认依据）；开关关闭或查询失败返回空串，不影响主链路 */
    private String renderRecentEventRefs(Long userId) {
        if (!agentProperties.isEventPipelineEnabled() || userId == null) {
            return "";
        }
        try {
            return eventService.renderRecentEventRefs(userId);
        } catch (Exception e) {
            log.warn("最近事件上下文获取失败，跳过注入: {}", e.getMessage());
            return "";
        }
    }

    /** 生成回复并做输出合规：轻度违规重生成（最多 N 次），中/重度直接拦截 */
    public String generateWithCompliance(RouteOutcome outcome) {
        int maxAttempts = agentProperties.getMaxRegenerateTimes() + 1;
        for (int attempt = 1; ; attempt++) {
            AgentResult<String> generated = invoker.invoke(AgentType.CONVERSATION, "generate_reply",
                    () -> conversationAgent.generateReply(outcome.scene(), outcome.vars()));
            String reply = generated.getData() != null
                    ? generated.getData() : ComplianceAgent.MODERATE_FALLBACK;
            ComplianceResult outputCheck = checkOutput(reply);
            if (outputCheck.isPassed()) {
                return reply;
            }
            if (outputCheck.getLevel() != ViolationLevel.MILD || attempt >= maxAttempts) {
                return fallbackReply(outputCheck);
            }
            log.warn("输出合规未通过（轻度），触发重生成: attempt={}, suggestion={}",
                    attempt, outputCheck.getSuggestion());
        }
    }

    /** 用户消息落库 + 向量归档（不写内存上下文；合并轮的上下文在 archiveContext 统一回写） */
    public Message archiveUserMessage(Conversation conversation, Long userId, String content) {
        Message userMessage = conversationService.saveMessage(
                conversation.getId(), userId, Message.ROLE_USER, content);
        vectorArchiveService.archiveMessage(userMessage);
        return userMessage;
    }

    /**
     * 全量归档：本轮全部来源消息打标（碎片合并轮逐碎片溯源）+ 上下文回写 Redis。
     * 日常闲聊（Scene.DAILY）不写 tag_record：无画像/事件价值，避免稀释统计；
     * 安抚/咨询/澄清/任务分支照常落标。tag 的事件归因（event_id）由事件链路异步回填。
     *
     * @param sourceMessageIds 本轮输入对应的用户消息 ID 集（普通轮单条、合并轮多条）
     * @param userTurnText     写入上下文的用户轮文本（合并轮为合并后全文）
     * @param scene            对话场景（打标跳过判定依据）
     */
    public void archiveContext(ConversationContext context, Long userId, List<Long> sourceMessageIds,
                               String userTurnText, LanguageAnalysisResult analysis, String reply,
                               ConversationAgent.Scene scene) {
        if (scene != ConversationAgent.Scene.DAILY) {
            for (Long messageId : sourceMessageIds) {
                tagService.saveTags(messageId, userId, analysis.getTags());
            }
        }
        context.appendMessage(Message.ROLE_USER, userTurnText,
                sourceMessageIds.get(sourceMessageIds.size() - 1));
        context.appendMessage(Message.ROLE_SYSTEM, reply);
        context.setLatestTags(analysis.getTags());
        contextManager.save(context);
    }

    /** 组装最终对话响应（replyType 取路由分支值） */
    public ChatResponse buildResponse(Long conversationId, Long userMessageId, Long replyMessageId,
                                      String reply, RouteOutcome route, LanguageAnalysisResult analysis) {
        return ChatResponse.builder()
                .conversationId(conversationId)
                .userMessageId(userMessageId)
                .replyMessageId(replyMessageId)
                .reply(reply)
                .replyType(route.replyType())
                .emotionTone(emotionTone(analysis))
                .tags(analysis.getTags())
                .taskId(route.taskId())
                .needClarify(analysis.isNeedClarify())
                .build();
    }

    /** 输入合规拦截响应 */
    public ChatResponse buildIntercepted(Long conversationId, String reply) {
        return ChatResponse.builder()
                .conversationId(conversationId)
                .reply(reply)
                .replyType("intercepted")
                .needClarify(false)
                .build();
    }

    /** 碎片归集待定响应：本条已缓冲待续说，不生成回复 */
    public ChatResponse buildPending(Long conversationId, Long userMessageId) {
        return ChatResponse.builder()
                .conversationId(conversationId)
                .userMessageId(userMessageId)
                .replyType(ChatResponse.REPLY_TYPE_PENDING)
                .needClarify(false)
                .build();
    }

    /** 情绪基调（首情绪标签，缺省「平静」） */
    public String emotionTone(LanguageAnalysisResult analysis) {
        return analysis.getTags().stream()
                .filter(tag -> TagItem.CATEGORY_EMOTION.equals(tag.getCategory()))
                .map(TagItem::getName)
                .findFirst()
                .orElse("平静");
    }

    private ComplianceResult invokeCompliance(String text, TextType type) {
        AgentResult<ComplianceResult> result = invoker.invoke(AgentType.COMPLIANCE,
                "check_" + type.name().toLowerCase(),
                () -> switch (type) {
                    case INPUT -> complianceAgent.checkInput(text);
                    case OUTPUT -> complianceAgent.checkOutput(text);
                    case REPORT -> complianceAgent.checkReport(text);
                });
        // 合规校验自身失败时保守拦截（安全优先）
        return result.getData() != null ? result.getData()
                : ComplianceResult.reject(ViolationLevel.MODERATE, "compliance_error", null,
                        ComplianceAgent.MODERATE_FALLBACK);
    }
}
