package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.ConversationAgent;
import com.mirror.agent.dp.entity.ConversationContext;
import com.mirror.agent.entity.Conversation;
import com.mirror.agent.entity.Message;
import com.mirror.agent.message.ChatRequest;
import com.mirror.agent.message.ChatResponse;
import com.mirror.agent.message.ComplianceResult;
import com.mirror.agent.message.LanguageAnalysisResult;
import com.mirror.agent.service.ConversationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * DP 动态规划调度 Agent —— 全局中枢与唯一中转节点（docs/agent_definition.md §2.1）。
 *
 * <p>标准串行链路（scene.md 场景 1/2/3）：输入合规 → 归档用户消息 → 语言分析（含完整性
 * fragment 判定）→ 意图路由 → 生成回复 → 输出合规（重生成）→ 归档标签/向量 → 响应。
 * 全链编排（含碎片归集判定，doc/modify/碎片消息归集与主动追问-后端修改记录-20261012.md）
 * 委托 {@link AggregationCoordinator}，链路原子步骤收敛于 {@link ChatChainSupport}；
 * 流式本体由 ChatService 组合，收尾统一走 {@link #finalizeStream}。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DpScheduleAgent {

    private final AggregationCoordinator coordinator;
    private final ChatChainSupport chainSupport;
    private final ConversationService conversationService;

    /**
     * SSE 流式链路前置准备结果。
     * 碎片归集语义：replyType=pending 表示本条已被缓冲待续说；
     * fragmentMessageIds/mergedContent 非空表示合并轮次（用户消息已在归集环节落库）。
     */
    public record StreamPreparation(Conversation conversation, ConversationContext context,
                                    Message userMessage, LanguageAnalysisResult analysis,
                                    ConversationAgent.Scene scene, Map<String, String> vars,
                                    String replyType, String interceptedReply,
                                    List<Long> fragmentMessageIds, String mergedContent) {

        public boolean intercepted() {
            return interceptedReply != null;
        }

        public boolean pending() {
            return ChatResponse.REPLY_TYPE_PENDING.equals(replyType);
        }

        /** 归集待定态：SSE 返回空流，后续由会话级订阅通道推送或重开拉取 */
        public static StreamPreparation pending(Conversation conversation) {
            return new StreamPreparation(conversation, null, null, null, null, null,
                    ChatResponse.REPLY_TYPE_PENDING, null, null, null);
        }
    }

    /** 同步对话主流程（含碎片归集判定） */
    public ChatResponse handleChat(Long userId, ChatRequest request) {
        return coordinator.handleChat(userId, request);
    }

    /** 流式链路前置：归集判定 + 输入合规 + 归档 + 分析 + 路由（生成与收尾由 ChatService 组合） */
    public StreamPreparation prepareStream(Long userId, ChatRequest request) {
        return coordinator.prepareStream(userId, request);
    }

    /**
     * 流式收尾：聚合全文做输出合规（不通过则留痕并以拦截话术归档）+ 全量归档。
     * 合并轮次按全部碎片消息溯源打标，上下文写入合并后全文。
     */
    public void finalizeStream(StreamPreparation preparation, String fullReply) {
        Long userId = preparation.context().getUserId();
        ComplianceResult outputCheck = chainSupport.checkOutput(fullReply);
        String finalReply = outputCheck.isPassed() ? fullReply : chainSupport.fallbackReply(outputCheck);
        if (!outputCheck.isPassed()) {
            log.warn("流式输出合规未通过，已归档拦截话术: conversationId={}",
                    preparation.conversation().getId());
        }
        conversationService.saveMessage(preparation.conversation().getId(),
                userId, Message.ROLE_SYSTEM, finalReply);
        List<Long> sourceIds = preparation.fragmentMessageIds() != null
                ? preparation.fragmentMessageIds() : List.of(preparation.userMessage().getId());
        String userTurnText = preparation.mergedContent() != null
                ? preparation.mergedContent() : preparation.userMessage().getContent();
        chainSupport.archiveContext(preparation.context(), userId, sourceIds, userTurnText,
                preparation.analysis(), finalReply, preparation.scene());
    }
}
