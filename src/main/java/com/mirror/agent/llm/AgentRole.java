package com.mirror.agent.llm;

/**
 * LLM 调用角色：每个角色对应一个独立 ChatClient（独立 System Prompt + 路由策略）。
 * configKey 对应 mirror.llm.routing.&lt;configKey&gt;；systemPromptFile 为 prompts/system/ 下的角色文件。
 */
public enum AgentRole {

    /** 用户对话生成（ConversationAgent，含流式） */
    CONVERSATION("conversation", "conversation-agent.txt"),

    /** 内容合规审查（ComplianceAgent，安全关键，禁止降级） */
    COMPLIANCE("compliance", "compliance-agent.txt"),

    /** 事件同一性裁决（EventAgent，结构化输出） */
    EVENT("event", "event-agent.txt"),

    /** 语言分析打标 + 事件抽取（LanguageAnalysisAgent，结构化输出） */
    LANGUAGE_ANALYSIS("language-analysis", "language-analysis-agent.txt"),

    /** 画像报告撰写（ProfileReportAgent，自由 Markdown） */
    PROFILE_REPORT("profile-report", "profile-report-agent.txt"),

    /** 临时摘要 Agent（DynamicAgentFactory 动态创建，重 token 轻质量） */
    TEMP_SUMMARIZE("temp-summarize", "temp-summarize.txt");

    private final String configKey;
    private final String systemPromptFile;

    AgentRole(String configKey, String systemPromptFile) {
        this.configKey = configKey;
        this.systemPromptFile = systemPromptFile;
    }

    public String configKey() {
        return configKey;
    }

    public String systemPromptFile() {
        return systemPromptFile;
    }
}
