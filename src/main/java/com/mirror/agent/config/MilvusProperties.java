package com.mirror.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Milvus 连接与集合配置（mirror.milvus.*）。
 */
@Data
@ConfigurationProperties(prefix = "mirror.milvus")
public class MilvusProperties {

    private String host = "localhost";

    private int port = 19530;

    /** 对话向量集合 */
    private String messageCollection = "message_vector";

    /** 知识库向量集合 */
    private String knowledgeCollection = "knowledge_vector";

    /** 嵌入向量维度（需与嵌入模型一致） */
    private int embeddingDim = 1024;
}
