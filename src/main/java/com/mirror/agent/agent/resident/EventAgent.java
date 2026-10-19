package com.mirror.agent.agent.resident;

import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.llm.AgentRole;
import com.mirror.agent.llm.LlmGateway;
import com.mirror.agent.llm.LlmRequest;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.EventJudgeResult;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 事件分析 Agent：查重灰区（相似度落在上下阈值之间）的「是否同一事件」裁决。
 * 仅在低频灰区命中时被调用；调用失败（LlmGateway 已做重试）按「不同事件」降级
 * （保守新建，不错误合并）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAgent {

    private static final String TEMPLATE = "event-dedup-judge.txt";

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
}
