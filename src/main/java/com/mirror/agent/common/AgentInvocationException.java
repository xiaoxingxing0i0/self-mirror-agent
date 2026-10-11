package com.mirror.agent.common;

import com.mirror.agent.message.AgentType;
import lombok.Getter;

/**
 * Agent 调用失败异常：触发 Resilience4j 重试，超过阈值后由降级兜底逻辑接管。
 */
@Getter
public class AgentInvocationException extends RuntimeException {

    private final AgentType agentType;
    private final String action;

    public AgentInvocationException(AgentType agentType, String action, String reason) {
        super("Agent 调用失败: " + agentType + "/" + action + " - " + reason);
        this.agentType = agentType;
        this.action = action;
    }
}
