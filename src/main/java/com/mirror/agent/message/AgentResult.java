package com.mirror.agent.message;

import com.mirror.agent.common.ErrorCode;
import lombok.Data;

import java.io.Serializable;

/**
 * Agent 统一执行结果。DP 调度 Agent 依据 success/errorCode 做重试、降级与熔断决策。
 */
@Data
public class AgentResult<T> implements Serializable {

    private boolean success;
    private String errorCode;
    private String errorMessage;
    private T data;
    /** 是否由降级逻辑产生（兜底结果） */
    private boolean degraded;

    public static <T> AgentResult<T> ok(T data) {
        AgentResult<T> result = new AgentResult<>();
        result.setSuccess(true);
        result.setErrorCode(ErrorCode.SUCCESS.getCode());
        result.setData(data);
        return result;
    }

    public static <T> AgentResult<T> fail(ErrorCode errorCode, String message) {
        AgentResult<T> result = new AgentResult<>();
        result.setSuccess(false);
        result.setErrorCode(errorCode.getCode());
        result.setErrorMessage(message);
        return result;
    }

    /** 降级兜底结果：链路可继续推进，但需向用户说明数据受限 */
    public static <T> AgentResult<T> degraded(T data, String reason) {
        AgentResult<T> result = ok(data);
        result.setDegraded(true);
        result.setErrorMessage(reason);
        return result;
    }
}
