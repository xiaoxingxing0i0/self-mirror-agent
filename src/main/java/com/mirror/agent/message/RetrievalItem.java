package com.mirror.agent.message;

import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 统一检索结果封装：内容 + 相关性得分 + 元数据（时间/标签/来源）。
 */
@Data
@Builder
public class RetrievalItem implements Serializable {

    /** 命中内容片段 */
    private String content;

    /** 相关性得分（向量相似度，越大越相关） */
    private double score;

    /** 来源集合：message_vector / knowledge_vector */
    private String source;

    /** 元数据：messageId、conversationId、createTime、tagCodes / docId、category、chunkIndex */
    private Map<String, Object> metadata;
}
