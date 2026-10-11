package com.mirror.agent.dp.entity;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import lombok.Getter;

import java.util.Map;
import java.util.Set;

/**
 * 任务状态枚举 + 轻量状态机（docs/agent_implementation.md §3.5）。
 * 通过合法流转表禁止非法状态跳转。
 */
@Getter
public enum TaskState {

    PENDING(0, "待执行"),
    RUNNING(1, "执行中"),
    COMPLETED(2, "已完成"),
    FAILED(3, "已失败"),
    CANCELLED(4, "已取消");

    private final int code;
    private final String label;

    /** 合法流转表：当前状态 → 允许迁移到的状态集合 */
    private static final Map<TaskState, Set<TaskState>> TRANSITIONS = Map.of(
            PENDING, Set.of(RUNNING, CANCELLED),
            RUNNING, Set.of(COMPLETED, FAILED, CANCELLED),
            FAILED, Set.of(PENDING, RUNNING),
            COMPLETED, Set.of(),
            CANCELLED, Set.of()
    );

    TaskState(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public static TaskState fromCode(int code) {
        for (TaskState state : values()) {
            if (state.code == code) {
                return state;
            }
        }
        throw new BizException(ErrorCode.TASK_STATE_ILLEGAL, "未知任务状态码: " + code);
    }

    /** 校验并返回目标状态，非法流转抛出业务异常 */
    public TaskState transferTo(TaskState target) {
        if (!TRANSITIONS.getOrDefault(this, Set.of()).contains(target)) {
            throw new BizException(ErrorCode.TASK_STATE_ILLEGAL,
                    "非法状态流转: " + this + " -> " + target);
        }
        return target;
    }

    public boolean isFinished() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
