package com.mirror.agent.dp.entity;

import com.mirror.agent.message.TagItem;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话上下文（Redis 存储，24 小时过期）。
 * 每次请求通过 conversationId 还原，实现多轮对话记忆继承。
 */
@Data
public class ConversationContext implements Serializable {

    /** 上下文中的单条消息 */
    @Data
    public static class ContextMessage implements Serializable {
        /** 0 用户 1 系统 */
        private int role;
        private String content;
        private LocalDateTime time;

        public ContextMessage() {
        }

        public ContextMessage(int role, String content, LocalDateTime time) {
            this.role = role;
            this.content = content;
            this.time = time;
        }
    }

    private Long conversationId;

    private Long userId;

    /** 最近消息（按 maxRounds 轮截取，更早消息由摘要压缩替代） */
    private List<ContextMessage> recentMessages = new ArrayList<>();

    /** 更早消息的滚动摘要（超长上下文压缩） */
    private String historySummary;

    /** 最近一次语言分析标签 */
    private List<TagItem> latestTags = new ArrayList<>();

    /** 当前执行中的计划 ID */
    private String activePlanId;

    private LocalDateTime lastActiveTime;

    public void appendMessage(int role, String content) {
        recentMessages.add(new ContextMessage(role, content, LocalDateTime.now()));
        lastActiveTime = LocalDateTime.now();
    }

    /** 截取最近 maxRounds 轮（一轮 = 用户 + 系统各一条），超出部分丢弃前文 */
    public void trim(int maxRounds) {
        int maxMessages = maxRounds * 2;
        if (recentMessages.size() > maxMessages) {
            recentMessages = new ArrayList<>(
                    recentMessages.subList(recentMessages.size() - maxMessages, recentMessages.size()));
        }
    }

    /** 渲染为提示词可用的历史对话文本 */
    public String renderHistory() {
        StringBuilder builder = new StringBuilder();
        if (historySummary != null && !historySummary.isBlank()) {
            builder.append("[早期对话摘要] ").append(historySummary).append('\n');
        }
        for (ContextMessage message : recentMessages) {
            builder.append(message.getRole() == 0 ? "用户: " : "助手: ")
                    .append(message.getContent())
                    .append('\n');
        }
        return builder.toString();
    }
}
