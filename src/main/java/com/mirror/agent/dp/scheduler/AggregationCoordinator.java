package com.mirror.agent.dp.scheduler;

import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.dp.entity.ConversationContext;
import com.mirror.agent.dp.scheduler.DpScheduleAgent.StreamPreparation;
import com.mirror.agent.dp.scheduler.IntentRouter.RouteOutcome;
import com.mirror.agent.entity.Conversation;
import com.mirror.agent.entity.Message;
import com.mirror.agent.message.ChatRequest;
import com.mirror.agent.message.ChatResponse;
import com.mirror.agent.message.ComplianceResult;
import com.mirror.agent.message.ConversationClosedEvent;
import com.mirror.agent.message.FragmentBuffer;
import com.mirror.agent.message.FragmentBuffer.Fragment;
import com.mirror.agent.message.LanguageAnalysisResult;
import com.mirror.agent.service.ChatPushService;
import com.mirror.agent.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 碎片归集协调器（话未说完→等待续说→超时主动追问），对话链路的实际编排者。
 * 完整性判定：高置信规则为「明显碎片快速通道」，其余由 analyze 的 fragment 字段全量判定（零额外调用）；
 * 滑动窗口 + Redis ZSET 定时器（ZREM 认领多实例互斥）；追问先落库再 SSE 投递，离线重开拉历史兜底；
 * 归集关闭时退化为标准链路直通。事件链路完成后异步交 {@link EventResolver} 处理。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggregationCoordinator {

    private final AgentProperties agentProperties;
    private final MessageAggregator aggregator;
    private final FragmentDetector fragmentDetector;
    private final ChatChainSupport chainSupport;
    private final ConversationContextManager contextManager;
    private final ConversationService conversationService;
    private final IntentRouter intentRouter;
    private final ChatPushService pushService;
    private final EventResolver eventResolver;

    // ==================== 同步对话入口 ====================

    /** 同步对话全链路（含归集判定） */
    public ChatResponse handleChat(Long userId, ChatRequest request) {
        Conversation conversation = conversationService.getOrCreate(userId, request.getConversationId());
        request.setConversationId(conversation.getId());
        if (gatingEnabled() && aggregator.hasPending(conversation.getId())) {
            return continuedChatFlow(conversation, userId, request);
        }
        return freshChatFlow(conversation, userId, request);
    }

    /** 无进行中归集窗口：合规 → 归档 → 快速通道或标准分析 → fragment 门 */
    private ChatResponse freshChatFlow(Conversation conversation, Long userId, ChatRequest request) {
        ComplianceResult check = chainSupport.checkInput(request.getContent());
        if (!check.isPassed()) {
            return chainSupport.buildIntercepted(conversation.getId(), chainSupport.fallbackReply(check));
        }
        Message userMessage = chainSupport.archiveUserMessage(
                conversation, userId, request.getContent());
        if (gatingEnabled() && fragmentDetector.isObviousFragment(request.getContent())) {
            bufferFreshFragment(conversation, userId, userMessage);
            return chainSupport.buildPending(conversation.getId(), userMessage.getId());
        }
        ConversationContext context = loadAndSnapshot(conversation.getId(), userId);
        LanguageAnalysisResult analysis = chainSupport.analyze(request.getContent(), context);
        if (gatingEnabled() && analysis.isFragment()) {
            bufferFreshFragment(conversation, userId, userMessage);
            return chainSupport.buildPending(conversation.getId(), userMessage.getId());
        }
        ChatResponse response = executeTurn(conversation, userId, request.getContent(),
                List.of(userMessage.getId()), analysis, context);
        eventResolver.resolveAsync(userId, conversation.getId(),
                List.of(userMessage.getId()), request.getContent(), analysis);
        return response;
    }

    /** 窗口内续说：合规先行（不过则窗口原样保留）→ 原子认领 → 合并文本分析一次，路由复用同一结果 */
    private ChatResponse continuedChatFlow(Conversation conversation, Long userId, ChatRequest request) {
        ComplianceResult check = chainSupport.checkInput(request.getContent());
        if (!check.isPassed()) {
            return chainSupport.buildIntercepted(conversation.getId(), chainSupport.fallbackReply(check));
        }
        if (!aggregator.claim(conversation.getId())) {
            return freshChatFlow(conversation, userId, request);
        }
        Message current = chainSupport.archiveUserMessage(conversation, userId, request.getContent());
        FragmentBuffer buffer = aggregator.takeBuffer(conversation.getId());
        List<Fragment> fragments = collectFragments(buffer, current);
        long firstTs = firstTs(buffer);
        ConversationContext context = loadAndSnapshot(conversation.getId(), userId);
        String merged = aggregator.mergeText(fragments);
        LanguageAnalysisResult analysis = chainSupport.analyze(merged, context);
        boolean overflowed = aggregator.overflowed(firstTs, fragments.size());
        if (!overflowed && analysis.isFragment()) {
            rebufferContinued(conversation, userId, firstTs, fragments);
            return chainSupport.buildPending(conversation.getId(), current.getId());
        }
        boolean forcedClarify = analysis.isFragment();
        if (forcedClarify) {
            analysis.setNeedClarify(true);
        }
        ChatResponse response = executeTurn(conversation, userId, merged,
                messageIds(fragments), analysis, context);
        if (!forcedClarify) {
            eventResolver.resolveAsync(userId, conversation.getId(),
                    messageIds(fragments), merged, analysis);
        }
        return response;
    }

    // ==================== 流式对话入口 ====================

    /** 流式链路前置准备（含归集判定）；生成与收尾仍由 ChatService/DpScheduleAgent 组合 */
    public StreamPreparation prepareStream(Long userId, ChatRequest request) {
        Conversation conversation = conversationService.getOrCreate(userId, request.getConversationId());
        request.setConversationId(conversation.getId());
        if (gatingEnabled() && aggregator.hasPending(conversation.getId())) {
            return continuedStreamFlow(conversation, userId, request);
        }
        return freshStreamFlow(conversation, userId, request);
    }

    private StreamPreparation freshStreamFlow(Conversation conversation, Long userId, ChatRequest request) {
        ComplianceResult check = chainSupport.checkInput(request.getContent());
        if (!check.isPassed()) {
            return new StreamPreparation(conversation, null, null, null, null, null,
                    "intercepted", chainSupport.fallbackReply(check), null, null);
        }
        Message userMessage = chainSupport.archiveUserMessage(
                conversation, userId, request.getContent());
        if (gatingEnabled() && fragmentDetector.isObviousFragment(request.getContent())) {
            bufferFreshFragment(conversation, userId, userMessage);
            return StreamPreparation.pending(conversation);
        }
        ConversationContext context = loadAndSnapshot(conversation.getId(), userId);
        LanguageAnalysisResult analysis = chainSupport.analyze(request.getContent(), context);
        if (gatingEnabled() && analysis.isFragment()) {
            bufferFreshFragment(conversation, userId, userMessage);
            return StreamPreparation.pending(conversation);
        }
        eventResolver.resolveAsync(userId, conversation.getId(),
                List.of(userMessage.getId()), request.getContent(), analysis);
        return routedPreparation(conversation, context, analysis, request.getContent(),
                userMessage, null, null);
    }

    private StreamPreparation continuedStreamFlow(Conversation conversation, Long userId,
                                                  ChatRequest request) {
        ComplianceResult check = chainSupport.checkInput(request.getContent());
        if (!check.isPassed()) {
            return new StreamPreparation(conversation, null, null, null, null, null,
                    "intercepted", chainSupport.fallbackReply(check), null, null);
        }
        if (!aggregator.claim(conversation.getId())) {
            return freshStreamFlow(conversation, userId, request);
        }
        Message current = chainSupport.archiveUserMessage(conversation, userId, request.getContent());
        FragmentBuffer buffer = aggregator.takeBuffer(conversation.getId());
        List<Fragment> fragments = collectFragments(buffer, current);
        long firstTs = firstTs(buffer);
        ConversationContext context = loadAndSnapshot(conversation.getId(), userId);
        String merged = aggregator.mergeText(fragments);
        LanguageAnalysisResult analysis = chainSupport.analyze(merged, context);
        boolean overflowed = aggregator.overflowed(firstTs, fragments.size());
        if (!overflowed && analysis.isFragment()) {
            log.info("未达到归集上限，且需要归集");
            rebufferContinued(conversation, userId, firstTs, fragments);
            return StreamPreparation.pending(conversation);
        }
        boolean forcedClarify = analysis.isFragment();
        if (forcedClarify) {
            analysis.setNeedClarify(true);
        }
        if (!forcedClarify) {
            eventResolver.resolveAsync(userId, conversation.getId(),
                    messageIds(fragments), merged, analysis);
        }
        return routedPreparation(conversation, context, analysis, merged,
                null, messageIds(fragments), merged);
    }

    /** 分析+路由产出流式准备结果；合并轮传 fragmentIds/mergedContent（收尾按碎片溯源） */
    private StreamPreparation routedPreparation(Conversation conversation, ConversationContext context,
                                                LanguageAnalysisResult analysis, String inputText,
                                                Message userMessage, List<Long> fragmentIds,
                                                String mergedContent) {
        RouteOutcome route = intentRouter.route(context.getUserId(), context,
                requestOf(conversation.getId(), inputText), analysis);
        return new StreamPreparation(conversation, context, userMessage, analysis,
                route.scene(), route.vars(), route.replyType(), null, fragmentIds, mergedContent);
    }

    // ==================== 超时 / 关闭冲刷 ====================

    /** 窗口到期冲刷：合并文本强制澄清 → 先落库 → 再经 SSE 通道投递（离线时重开拉取兜底） */
    public void flushTimedOut(Long conversationId) {
        FragmentBuffer buffer = aggregator.takeBuffer(conversationId);
        if (buffer == null || buffer.getFragments().isEmpty()) {
            log.warn("到期已认领但归集缓冲不存在（TTL 过期或已被消费），本轮不追问: conversationId={}",
                    conversationId);
            return;
        }
        Conversation conversation = conversationService.require(conversationId, buffer.getUserId());
        String merged = aggregator.mergeText(buffer.getFragments());
        ConversationContext context = loadAndSnapshot(conversationId, buffer.getUserId());
        LanguageAnalysisResult analysis = chainSupport.analyze(merged, context);
        analysis.setNeedClarify(true);
        ChatResponse response = executeTurn(conversation, buffer.getUserId(), merged,
                messageIds(buffer.getFragments()), analysis, context);
        pushService.publishAgentMessage(conversationId, response);
        log.info("归集窗口到期，Agent 已主动追问落库: conversationId={}, fragments={}",
                conversationId, buffer.getFragments().size());
    }

    /** 会话关闭事件：未完成归集立即冲刷落库（不等窗口到期），用户下次打开即可直接看到回复 */
    @Async
    @EventListener
    public void onConversationClosed(ConversationClosedEvent event) {
        if (!gatingEnabled() || !aggregator.claim(event.conversationId())) {
            return;
        }
        try {
            flushTimedOut(event.conversationId());
        } catch (Exception e) {
            log.error("会话关闭冲刷归集失败: conversationId={}", event.conversationId(), e);
        }
    }

    // ==================== 共用编排 ====================

    /** 轮内执行：路由 → 生成+输出合规 → 回复落库 → 溯源打标 + 上下文回写 */
    private ChatResponse executeTurn(Conversation conversation, Long userId, String inputText,
                                     List<Long> sourceMessageIds, LanguageAnalysisResult analysis,
                                     ConversationContext context) {
        RouteOutcome route = intentRouter.route(userId, context,
                requestOf(conversation.getId(), inputText), analysis);
        String reply = chainSupport.generateWithCompliance(route);
        Message replyMessage = conversationService.saveMessage(
                conversation.getId(), userId, Message.ROLE_SYSTEM, reply);
        chainSupport.archiveContext(context, userId, sourceMessageIds, inputText, analysis, reply,
                route.scene());
        return chainSupport.buildResponse(conversation.getId(),
                sourceMessageIds.get(sourceMessageIds.size() - 1),
                replyMessage.getId(), reply, route, analysis);
    }

    private boolean gatingEnabled() {
        return agentProperties.isFragmentAggregationEnabled();
    }

    private ConversationContext loadAndSnapshot(Long conversationId, Long userId) {
        ConversationContext context = contextManager.load(conversationId, userId);
        contextManager.snapshot(context);
        return context;
    }

    /** 开新归集窗口：写缓冲 + 滑动截止时间 + 推送待定态 */
    private void bufferFreshFragment(Conversation conversation, Long userId, Message fragment) {
        long now = System.currentTimeMillis();
        FragmentBuffer buffer = new FragmentBuffer(conversation.getId(), userId, now,
                new ArrayList<>(List.of(new Fragment(fragment.getId(), fragment.getContent(), now))));
        pushService.publishPending(conversation.getId(),
                aggregator.rebuffer(conversation.getId(), buffer));
    }

    /** 滑动续期归集窗口（含本轮新碎片），等待下一次续说 */
    private void rebufferContinued(Conversation conversation, Long userId,
                                   long firstTs, List<Fragment> fragments) {
        pushService.publishPending(conversation.getId(), aggregator.rebuffer(conversation.getId(),
                new FragmentBuffer(conversation.getId(), userId, firstTs, new ArrayList<>(fragments))));
    }

    private List<Fragment> collectFragments(FragmentBuffer buffer, Message current) {
        List<Fragment> fragments = new ArrayList<>();
        if (buffer != null) {
            fragments.addAll(buffer.getFragments());
        }
        fragments.add(new Fragment(current.getId(), current.getContent(), System.currentTimeMillis()));
        return fragments;
    }

    private long firstTs(FragmentBuffer buffer) {
        return buffer != null && buffer.getFirstTs() > 0
                ? buffer.getFirstTs() : System.currentTimeMillis();
    }

    private List<Long> messageIds(List<Fragment> fragments) {
        return fragments.stream().map(Fragment::getMessageId).toList();
    }

    private ChatRequest requestOf(Long conversationId, String content) {
        ChatRequest mergedRequest = new ChatRequest();
        mergedRequest.setConversationId(conversationId);
        mergedRequest.setContent(content);
        return mergedRequest;
    }
}
