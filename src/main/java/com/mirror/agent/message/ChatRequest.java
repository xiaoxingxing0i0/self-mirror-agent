package com.mirror.agent.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 用户对话请求 DTO（接口层 → DP 调度 Agent）。
 */
@Data
public class ChatRequest implements Serializable {

    /** 会话 ID，为空时由 DP 调度 Agent 创建新会话 */
    private Long conversationId;

    /** 用户原始输入内容 */
    @NotBlank(message = "消息内容不能为空")
    @Size(max = 4000, message = "单条消息不能超过4000字")
    private String content;
}
