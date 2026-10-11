package com.mirror.agent.message;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/**
 * 对话最终响应 DTO（DP 调度 Agent → 接口层）。
 */
@Data
@Builder
public class ChatResponse implements Serializable {

    /** 会话 ID */
    private Long conversationId;

    /** 用户消息 ID（归档后可溯源） */
    private Long userMessageId;

    /** 系统回复消息 ID */
    private Long replyMessageId;

    /** 面向用户的最终回复文本 */
    private String reply;

    /** 回复类型：chat / consult / task_notify / intercepted */
    private String replyType;

    /** 情绪基调（用于前端展示） */
    private String emotionTone;

    /** 本条消息命中的标签（溯源展示） */
    private List<TagItem> tags;

    /** 关联任务 ID（任务分支时返回） */
    private Long taskId;

    /** 标签置信度不足，回复内容为引导澄清话术 */
    private boolean needClarify;
}
