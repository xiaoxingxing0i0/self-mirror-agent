package com.mirror.agent.agent.temp;

import com.mirror.agent.message.AgentResult;

import java.util.Map;

/**
 * 动态临时 Agent 接口：为一次性专项任务（摘要、翻译、格式转换等）按需创建，
 * 用后即弃，不注册进常驻 Agent 池。由 DynamicAgentFactory 创建、DP 调度 Agent 统一调用。
 */
public interface TempAgent {

    /** 临时 Agent 名称（日志与链路追踪标识） */
    String name();

    /** 执行专项任务，params 由创建方约定 */
    AgentResult<String> execute(Map<String, Object> params);
}
