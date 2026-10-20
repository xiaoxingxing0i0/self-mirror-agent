package com.mirror.agent.agent.resident;

import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.llm.AgentRole;
import com.mirror.agent.llm.LlmGateway;
import com.mirror.agent.llm.LlmRequest;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.EventJudgeResult;
import com.mirror.agent.message.EventSummaryResult;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 事件分析 Agent：① 查重灰区（相似度落在上下阈值之间）的「是否同一事件」裁决；
 * ② 封稿时按事实列表归并生成现状摘要（事件链路 v2，取代逐轮重写）。
 * 调用失败（LlmGateway 已做重试）保守降级：裁决按「不同事件」，摘要返回空由调用方保留 bootstrap。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAgent {

    private static final String TEMPLATE = "event-dedup-judge.txt";
    private static final String SUMMARY_TEMPLATE = "event-summary.txt";

    private final LlmGateway llmGateway;
    private final PromptUtil promptUtil;

    /** 裁决两个事件描述是否同一件事 */
    public AgentResult<EventJudgeResult> judgeSameEvent(String existingDesc, String candidateDesc) {
        String prompt = promptUtil.render(TEMPLATE, Map.of(
                "existing", existingDesc, "candidate", candidateDesc));
        try {
            EventJudgeResult result = llmGateway.callStructured(
                    LlmRequest.of(AgentRole.EVENT, prompt), EventJudgeResult.class);
            if (result == null) {
                throw new IllegalStateException("模型返回空裁决结果");
            }
            return AgentResult.ok(result);
        } catch (Exception e) {
            log.warn("同事件裁决失败，保守判为不同事件: {}", e.getMessage());
            return AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "同事件裁决降级");
        }
    }

    /**
     * 封稿时依据事实列表归并生成现状摘要。coreFactsText / preEmotionHint 允许空串。
     * 失败返回 data=null 的降级结果，调用方保留 bootstrap 摘要（不阻断定稿）。
     */
    public AgentResult<EventSummaryResult> summarizeEvent(String name, String factsText,
                                                          String coreFactsText, String preEmotionHint) {
        String prompt = promptUtil.render(SUMMARY_TEMPLATE, Map.of(
                "name", name == null ? "" : name,
                "facts", factsText == null ? "" : factsText,
                "coreFacts", coreFactsText == null || coreFactsText.isBlank() ? "（无）" : coreFactsText,
                "preEmotionHint", preEmotionHint == null || preEmotionHint.isBlank()
                        ? "（无）" : preEmotionHint));
        try {
            EventSummaryResult result = llmGateway.callStructured(
                    LlmRequest.of(AgentRole.EVENT, prompt), EventSummaryResult.class);
            if (result == null || result.getSummary() == null || result.getSummary().isBlank()) {
                throw new IllegalStateException("模型返回空摘要");
            }
            return AgentResult.ok(result);
        } catch (Exception e) {
            log.warn("现状摘要生成失败，保留 bootstrap 摘要: {}", e.getMessage());
            return AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "摘要生成降级");
        }
    }
}
