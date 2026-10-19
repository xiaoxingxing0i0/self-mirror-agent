package com.mirror.agent.message;

/**
 * Agent 池节点类型（docs/agent_definition.md）。
 * 所有节点间交互均通过 DP 调度 Agent 转发。
 */
public enum AgentType {

    /** DP 动态规划调度 Agent（全局中枢） */
    DP_SCHEDULER,

    /** 语言分析 Agent */
    LANGUAGE_ANALYSIS,

    /** 用户对话 Agent */
    CONVERSATION,

    /** 检索 Agent */
    RETRIEVAL,

    /** 任务执行 Agent */
    TASK_EXECUTION,

    /** 合规检查 Agent */
    COMPLIANCE,

    /** 用户画像与报告 Agent */
    PROFILE_REPORT,

    /** 事件分析 Agent（查重灰区同事件裁决） */
    EVENT
}
