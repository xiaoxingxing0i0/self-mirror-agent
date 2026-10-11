package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * RAG 知识库表 knowledge_doc（docs/data_model.md §4.2.9）。
 */
@Data
@TableName("knowledge_doc")
public class KnowledgeDoc {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 文档标题 */
    private String title;

    /** 分类：情绪调节 / 心理科普 / 自我成长 */
    private String category;

    /** 文档原文 */
    private String content;

    /** 分块数量 */
    private Integer chunkCount;

    /** 状态：0 未向量化 1 已向量化 2 已禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static final int STATUS_PENDING = 0;
    public static final int STATUS_VECTORIZED = 1;
    public static final int STATUS_DISABLED = 2;
}
