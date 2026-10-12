package com.mirror.agent.message;

/**
 * 会话关闭事件：通知归集协调器立即冲刷该会话未完成的碎片缓冲
 * （不等滑动窗口到期，用户下次打开即可直接看到 Agent 回复）。
 */
public record ConversationClosedEvent(Long conversationId, Long userId) {
}
