package com.mirror.agent.dp.scheduler;

import com.mirror.agent.common.AgentInvocationException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.AgentType;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Agent 调用门面（docs/agent_implementation.md §3.1：门面模式封装所有 Agent 调用）。
 * 统一封装为可重试单元：Resilience4j 重试 + 熔断，失败按业务等级降级。
 */
@Slf4j
@Component
public class AgentInvoker {

    /**
     * 调用 Agent：结果为失败时抛出异常触发重试，重试耗尽后进入熔断降级。
     */
    @Retry(name = "agentCall")
    @CircuitBreaker(name = "agentCall", fallbackMethod = "fallback")
    public <T> AgentResult<T> invoke(AgentType agent, String action, Supplier<AgentResult<T>> call) {
        AgentResult<T> result = call.get();
        if (result == null || !result.isSuccess()) {
            String reason = result == null ? "空结果" : result.getErrorMessage();
            throw new AgentInvocationException(agent, action, reason);
        }
        return result;
    }

    /**
     * 降级兜底：返回失败结果并记录日志，由调用方按业务等级决定兜底话术
     * （检索失败 → 兜底话术；报告失败 → 提示稍后重试）。
     */
    @SuppressWarnings("unused")
    private <T> AgentResult<T> fallback(AgentType agent, String action,
                                        Supplier<AgentResult<T>> call, Throwable throwable) {
        log.error("Agent 调用降级: agent={}, action={}, reason={}", agent, action, throwable.getMessage());
        return AgentResult.fail(ErrorCode.SYSTEM_ERROR,
                "Agent 调用失败已降级: " + agent + "/" + action);
    }
}
