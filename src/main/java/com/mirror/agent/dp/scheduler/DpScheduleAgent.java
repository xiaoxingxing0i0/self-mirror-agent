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
import com.mirror.agent.message.ChatRequest;
import com.mirror.agent.message.ChatResponse;
import com.mirror.agent.message.ComplianceResult;
import com.mirror.agent.message.ComplianceResult.TextType;
import com.mirror.agent.message.ComplianceResult.ViolationLevel;
import com.mirror.agent.message.LanguageAnalysisResult;
import com.mirror.agent.message.TagItem;
import com.mirror.agent.service.ConversationService;
import com.mirror.agent.service.TagService;
import com.mirror.agent.service.VectorArchiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DP 动态规划调度 Agent —— 全局中枢与唯一中转节点（docs/agent_definition.md §2.1）。
 *
 * <p>标准串行链路（scene.md 场景 1/2/3）：
 * 输入合规 → 归档用户消息 → 语言分析 → 意图路由 → 生成回复 → 输出合规（重生成）→ 归档标签/向量 → 响应。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DpScheduleAgent {

    private final ConversationService conversationService;
    private final ConversationContextManager contextManager;
    private final AgentInvoker invoker;
    private final IntentRouter intentRouter;
    private final ComplianceAgent complianceAgent;
    private final LanguageAnalysisAgent languageAnalysisAgent;
    private final ConversationAgent conversationAgent;
    private final TagService tagService;
    private final VectorArchiveService vectorArchiveService;
    private final AgentProperties agentProperties;

    /** SSE 流式链路的前置准备结果 */
    public record StreamPreparation(Conversation conversation, ConversationContext context,
                                    Message userMessage, LanguageAnalysisResult analysis,
                                    ConversationAgent.Scene scene, Map<String, String> vars,
                                    String replyType, String interceptedReply) {

        public boolean intercepted() {
            return interceptedReply != null;
        }
    }

    /** 同步对话主流程 */
    public ChatResponse handleChat(Long userId, ChatRequest request) {
        Conversation conversation = conversationService.getOrCreate(userId, request.getConversationId());
        ConversationContext context = contextManager.load(conversation.getId(), userId);
        contextManager.snapshot(context);

        ComplianceResult inputCheck = invokeCompliance(request.getContent(), TextType.INPUT);
        if (!inputCheck.isPassed()) {
            return interceptedResponse(conversation.getId(), inputCheck);
        }
        Message userMessage = archiveUserMessage(conversation, userId, request.getContent(), context);
        LanguageAnalysisResult analysis = analyze(request.getContent(), context);
        RouteOutcome outcome = intentRouter.route(userId, context, request, analysis);
        String reply = generateWithCompliance(outcome);
        Message replyMessage = conversationService.saveMessage(
                conversation.getId(), userId, Message.ROLE_SYSTEM, reply);
        archiveContext(context, userMessage, userId, analysis, reply);
        return ChatResponse.builder()
                .conversationId(conversation.getId())
                .userMessageId(userMessage.getId())
                .replyMessageId(replyMessage.getId())
                .reply(reply)
                .replyType(outcome.replyType())
                .emotionTone(emotionTone(analysis))
                .tags(analysis.getTags())
                .taskId(outcome.taskId())
                .needClarify(analysis.isNeedClarify())
                .build();
    }

    /** 流式链路前置：输入合规 + 归档 + 分析 + 路由（生成与收尾由 ChatService 组合） */
    public StreamPreparation prepareStream(Long userId, ChatRequest request) {
        Conversation conversation = conversationService.getOrCreate(userId, request.getConversationId());
        ConversationContext context = contextManager.load(conversation.getId(), userId);
        contextManager.snapshot(context);
        ComplianceResult inputCheck = invokeCompliance(request.getContent(), TextType.INPUT);
        if (!inputCheck.isPassed()) {
            return new StreamPreparation(conversation, context, null, null, null, null,
                    "intercepted", fallbackReply(inputCheck));
        }
        Message userMessage = archiveUserMessage(conversation, userId, request.getContent(), context  );
        LanguageAnalysisResult analysis = analyze(request.getContent(), context);
        RouteOutcome outcome = intentRouter.route(userId, context, request, analysis);
        return new StreamPreparation(conversation, context, userMessage, analysis,
                outcome.scene(), outcome.vars(), outcome.replyType(), null);
    }

    /** 流式收尾：聚合全文做输出合规（不通过则留痕并以拦截话术归档）+ 全量归档 */
    public void finalizeStream(StreamPreparation preparation, String fullReply) {
        ComplianceResult outputCheck = invokeCompliance(fullReply, TextType.OUTPUT);
        String finalReply = outputCheck.isPassed() ? fullReply : fallbackReply(outputCheck);
        if (!outputCheck.isPassed()) {
            log.warn("流式输出合规未通过，已归档拦截话术: conversationId={}",
                    preparation.conversation().getId());
        }
        conversationService.saveMessage(preparation.conversation().getId(),
                preparation.userMessage().getUserId(), Message.ROLE_SYSTEM, finalReply);
        archiveContext(preparation.context(), preparation.userMessage(),
                preparation.userMessage().getUserId(), preparation.analysis(), finalReply);
    }

    /** 语言分析（失败时由 Agent 内部降级为待澄清闲聊；熔断时调度层兜底） */
    private LanguageAnalysisResult analyze(String content, ConversationContext context) {
        AgentResult<LanguageAnalysisResult> result = invoker.invoke(AgentType.LANGUAGE_ANALYSIS,
                "analyze", () -> languageAnalysisAgent.analyze(content, context.renderHistory()));
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

    /** 生成回复并做输出合规：轻度违规重生成（最多 N 次），中/重度直接拦截 */
    private String generateWithCompliance(RouteOutcome outcome) {
        int maxAttempts = agentProperties.getMaxRegenerateTimes() + 1;
        for (int attempt = 1; ; attempt++) {
            AgentResult<String> generated = invoker.invoke(AgentType.CONVERSATION, "generate_reply",
                    () -> conversationAgent.generateReply(outcome.scene(), outcome.vars()));
            String reply = generated.getData() != null
                    ? generated.getData() : ComplianceAgent.MODERATE_FALLBACK;
            ComplianceResult outputCheck = invokeCompliance(reply, TextType.OUTPUT);
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

    private String fallbackReply(ComplianceResult checkResult) {
        return checkResult.getInterceptedReply() != null
                ? checkResult.getInterceptedReply() : ComplianceAgent.MODERATE_FALLBACK;
    }

    private Message archiveUserMessage(Conversation conversation, Long userId,
                                       String content, ConversationContext context) {
        Message userMessage = conversationService.saveMessage(
                conversation.getId(), userId, Message.ROLE_USER, content);
        vectorArchiveService.archiveMessage(userMessage);
        context.appendMessage(Message.ROLE_USER, content);
        return userMessage;
    }

    /** 全量归档：标签落库（溯源）+ 上下文回写 Redis */
    private void archiveContext(ConversationContext context, Message userMessage, Long userId,
                                LanguageAnalysisResult analysis, String reply) {
        tagService.saveTags(userMessage.getId(), userId, analysis.getTags());
        context.appendMessage(Message.ROLE_SYSTEM, reply);
        context.setLatestTags(analysis.getTags());
        contextManager.save(context);
    }

    private ChatResponse interceptedResponse(Long conversationId, ComplianceResult inputCheck) {
        return ChatResponse.builder()
                .conversationId(conversationId)
                .reply(fallbackReply(inputCheck))
                .replyType("intercepted")
                .needClarify(false)
                .build();
    }

    private String emotionTone(LanguageAnalysisResult analysis) {
        return analysis.getTags().stream()
                .filter(tag -> TagItem.CATEGORY_EMOTION.equals(tag.getCategory()))
                .map(TagItem::getName)
                .findFirst()
                .orElse("平静");
    }
}
