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

    /** 连接用户名（实例开启认证时必填；空=匿名） */
    private String username = "root";

    /** 连接密码（空=匿名连接，兼容未开启认证的实例） */
    private String password = "";

    /** 对话向量集合 */
    private String messageCollection = "message_vector";

    /** 知识库向量集合 */
    private String knowledgeCollection = "knowledge_vector";

    /** 事由摘要向量集合（情绪-事由关联查重检索） */
    private String incidentCollection = "incident_vector";

    /** 事由增量事实向量集合（查重多路召回，一事实一向量，append-only） */
    private String factCollection = "incident_fact_vector";

    /** 嵌入向量维度（需与嵌入模型一致） */
    private int embeddingDim = 1024;
}
