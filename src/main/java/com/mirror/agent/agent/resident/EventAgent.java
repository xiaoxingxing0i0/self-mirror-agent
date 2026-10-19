package com.mirror.agent.agent.resident;

import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.EventJudgeResult;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 事件分析 Agent：查重灰区（相似度落在上下阈值之间）的「是否同一事件」裁决。
 * 仅在低频灰区命中时被调用；连续解析失败按「不同事件」降级（保守新建，不错误合并）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventAgent {

    private static final String TEMPLATE = "event-dedup-judge.txt";

    private final ChatClient chatClient;
    private final PromptUtil promptUtil;

    /** 裁决两个事件描述是否同一件事 */
    public AgentResult<EventJudgeResult> judgeSameEvent(String existingDesc, String candidateDesc) {
        String prompt = promptUtil.render(TEMPLATE, Map.of(
                "existing", existingDesc, "candidate", candidateDesc));
        EventJudgeResult result = callModel(prompt);
        if (result == null) {
            result = callModel(prompt);
        }
        if (result == null) {
            log.warn("同事件裁决结构化输出连续失败，保守判为不同事件");
            return AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "同事件裁决降级");
        }
        return AgentResult.ok(result);
    }

    private EventJudgeResult callModel(String prompt) {
        try {
            return chatClient.prompt().user(prompt).call().entity(EventJudgeResult.class);
        } catch (Exception e) {
            log.warn("同事件裁决模型调用失败: {}", e.getMessage());
            return null;
        }
    }
}
