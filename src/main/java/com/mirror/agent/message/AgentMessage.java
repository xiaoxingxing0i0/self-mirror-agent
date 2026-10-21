package com.mirror.agent.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * Agent 间通信消息 POJO。
 * 所有消息统一经 DP 调度 Agent 中转，可序列化后暂存 Redis。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage implements Serializable {

    /** 消息类型 */
    public enum Type {
        /** 调度指令 / 请求 */
        REQUEST,
        /** 执行结果响应 */
        RESPONSE,
        /** 状态事件（任务进度、生命周期通知） */
        INCIDENT,
        /** 执行异常 */
        ERROR
    }

    /** 消息唯一 ID */
    private String messageId;

    /** 全链路 TraceId（MDC 同步） */
    private String traceId;

    /** 发送方 Agent */
    private AgentType from;

    /** 接收方 Agent */
    private AgentType to;

    /** 消息类型 */
    private Type type;

    /** 业务内容类型标识，如 "chat" / "analysis" / "compliance" / "report" */
    private String contentType;

    /** 业务载荷 */
    private Map<String, Object> payload;

    /** 消息创建时间戳（毫秒） */
    private long timestamp;
}
