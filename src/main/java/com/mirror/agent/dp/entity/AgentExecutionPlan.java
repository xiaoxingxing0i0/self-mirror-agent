package com.mirror.agent.dp.entity;

import com.mirror.agent.message.AgentType;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * DP 调度 Agent 动态编排产出的执行计划。
 * 支持串行 / 并行 / 分支三种执行模式（docs/agent_implementation.md §3.1）。
 */
@Data
public class AgentExecutionPlan implements Serializable {

    /** 执行模式 */
    public enum Mode {
        /** 串行：分析 → 对话 → 合规 → 输出 */
        SERIAL,
        /** 并行：多维检索同时触发，结果聚合 */
        PARALLEL,
        /** 分支：按意图标签路由到任务 / 检索 / 对话分支 */
        BRANCH
    }

    private String planId;

    private Long conversationId;

    private Mode mode = Mode.SERIAL;

    /** 分支模式下命中的意图分支：chat / consult / query / task */
    private String branch;

    /** 有序执行节点 */
    private List<PlanStep> steps = new ArrayList<>();

    private TaskState state = TaskState.PENDING;

    public void addStep(AgentType agent, String action) {
        PlanStep step = new PlanStep();
        step.setStepOrder(steps.size() + 1);
        step.setAgentType(agent);
        step.setAction(action);
        steps.add(step);
    }

    /** 计划中的单个执行节点 */
    @Data
    public static class PlanStep implements Serializable {
        private int stepOrder;
        private AgentType agentType;
        /** 节点动作标识，如 "check_input" / "analyze" / "generate_reply" */
        private String action;
        private TaskState state = TaskState.PENDING;
    }
}
