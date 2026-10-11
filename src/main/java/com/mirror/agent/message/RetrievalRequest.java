package com.mirror.agent.message;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 检索 Agent 入参：双数据源（历史对话 / RAG 知识库）+ 多维过滤条件。
 */
@Data
@Builder
public class RetrievalRequest implements Serializable {

    /** 检索范围 */
    public enum Scope {
        /** 历史对话 */
        HISTORY,
        /** RAG 知识库 */
        KNOWLEDGE,
        /** 全部 */
        ALL
    }

    /** 强制必填：用户数据隔离（docs/agent_implementation.md §3.4） */
    private Long userId;

    /** 检索关键词 / 语义文本 */
    private String query;

    /** 检索范围限定 */
    private Scope scope;

    /** 返回条数上限 */
    private Integer topK;

    /** 时间范围过滤（仅历史对话） */
    private LocalDateTime startTime;
    private LocalDateTime endTime;

    /** 标签编码过滤（仅历史对话，如按情绪类型） */
    private List<String> tagCodes;
}
