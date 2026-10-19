package com.mirror.agent.llm;

/**
 * LLM 统一入口请求。
 *
 * @param role             调用角色（决定 System Prompt 与路由策略）
 * @param userText         已渲染好的 user 消息文本（模板渲染仍由各 Agent 走 PromptUtil）
 * @param extraSystem      追加的系统指令（拼接在角色 System Prompt 之后；可空）
 * @param providerOverride 显式指定 provider（跳过路由表主通道，且不做降级；可空）
 */
public record LlmRequest(AgentRole role, String userText, String extraSystem, Provider providerOverride) {

    public static LlmRequest of(AgentRole role, String userText) {
        return new LlmRequest(role, userText, null, null);
    }

    public static LlmRequest of(AgentRole role, String userText, Provider providerOverride) {
        return new LlmRequest(role, userText, null, providerOverride);
    }

    public static LlmRequest withSystem(AgentRole role, String userText, String extraSystem) {
        return new LlmRequest(role, userText, extraSystem, null);
    }
}
