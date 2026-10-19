package com.mirror.agent.repository;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.common.clientenum.ConsistencyLevelEnum;
import io.milvus.grpc.SearchResults;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.R;
import io.milvus.param.collection.CollectionSchemaParam;
import io.milvus.param.collection.CreateCollectionParam;
import io.milvus.param.collection.FieldType;
import io.milvus.param.collection.HasCollectionParam;
import io.milvus.param.collection.LoadCollectionParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import io.milvus.response.SearchResultsWrapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import io.milvus.grpc.DataType;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Milvus 集合初始化与通用检索支撑（docs/data_model.md §4.3）。
 * 启动时尝试建集合；Milvus 未就绪时降级为首次使用惰性初始化。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MilvusCollectionSupport {

    private final MilvusServiceClient client;
    private final MilvusProperties properties;

    private final AtomicBoolean messageReady = new AtomicBoolean(false);
    private final AtomicBoolean knowledgeReady = new AtomicBoolean(false);
    private final AtomicBoolean eventReady = new AtomicBoolean(false);

    @PostConstruct
    public void init() {
        try {
            ensureMessageCollection();
            ensureKnowledgeCollection();
            ensureEventCollection();
        } catch (Exception e) {
            log.warn("Milvus 集合初始化失败，将在首次使用时重试: {}", e.getMessage());
        }
    }

    /** event_vector：主键 event_id，分区键 user_id，冗余摘要与活跃时间（查重 rerank 用） */
    public void ensureEventCollection() {
        if (eventReady.get() || hasCollection(properties.getEventCollection())) {
            eventReady.set(true);
            return;
        }
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .addFieldType(longField("event_id", true, false))
                .addFieldType(longField("user_id", false, true))
                .addFieldType(longField("last_active_time", false, false))
                .addFieldType(contentField())
                .addFieldType(vectorField())
                .build();
        createAndLoad(properties.getEventCollection(), schema);
        eventReady.set(true);
        log.info("Milvus 集合已创建: {}", properties.getEventCollection());
    }

    /** message_vector：主键 message_id，分区键 user_id */
    public void ensureMessageCollection() {
        if (messageReady.get() || hasCollection(properties.getMessageCollection())) {
            messageReady.set(true);
            return;
        }
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .addFieldType(longField("message_id", true, false))
                .addFieldType(longField("user_id", false, true))
                .addFieldType(longField("conversation_id", false, false))
                .addFieldType(longField("create_time", false, false))
                .addFieldType(vectorField())
                .build();
        createAndLoad(properties.getMessageCollection(), schema);
        messageReady.set(true);
        log.info("Milvus 集合已创建: {}", properties.getMessageCollection());
    }

    /** knowledge_vector：主键 chunk_id，分区键 category */
    public void ensureKnowledgeCollection() {
        if (knowledgeReady.get() || hasCollection(properties.getKnowledgeCollection())) {
            knowledgeReady.set(true);
            return;
        }
        CollectionSchemaParam schema = CollectionSchemaParam.newBuilder()
                .addFieldType(longField("chunk_id", true, false))
                .addFieldType(longField("doc_id", false, false))
                .addFieldType(longField("chunk_index", false, false))
                .addFieldType(varCharField("category", true))
                .addFieldType(contentField())
                .addFieldType(vectorField())
                .build();
        createAndLoad(properties.getKnowledgeCollection(), schema);
        knowledgeReady.set(true);
        log.info("Milvus 集合已创建: {}", properties.getKnowledgeCollection());
    }

    /** 通用向量检索，expr 为标量过滤表达式（强制数据隔离条件由调用方拼接） */
    public List<VectorHit> search(String collection, List<Float> queryVector, int topK,
                                  String expr, List<String> outputFields) {
        SearchParam searchParam = SearchParam.newBuilder()
                .withCollectionName(collection)
                .withMetricType(MetricType.COSINE)
                .withConsistencyLevel(ConsistencyLevelEnum.BOUNDED)
                .withTopK(topK)
                .withVectors(List.of(queryVector))
                .withVectorFieldName("embedding")
                .withOutFields(outputFields)
                .withExpr(expr == null ? "" : expr)
                .build();
        R<SearchResults> response = client.search(searchParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new BizException(ErrorCode.MILVUS_ERROR, "向量检索失败: " + response.getMessage());
        }
        return toHits(response);
    }

    private List<VectorHit> toHits(R<SearchResults> response) {
        SearchResultsWrapper wrapper = new SearchResultsWrapper(response.getData().getResults());
        List<SearchResultsWrapper.IDScore> scores = wrapper.getIDScore(0);
        return scores.stream()
                .map(s -> new VectorHit(s.getLongID(), s.getScore(), s.getFieldValues()))
                .toList();
    }

    private boolean hasCollection(String name) {
        R<Boolean> response = client.hasCollection(
                HasCollectionParam.newBuilder().withCollectionName(name).build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new BizException(ErrorCode.MILVUS_ERROR, "Milvus 连接异常: " + response.getMessage());
        }
        return Boolean.TRUE.equals(response.getData());
    }

    private void createAndLoad(String name, CollectionSchemaParam schema) {
        R<?> created = client.createCollection(CreateCollectionParam.newBuilder()
                .withCollectionName(name)
                .withSchema(schema)
                .build());
        if (created.getStatus() != R.Status.Success.getCode()) {
            throw new BizException(ErrorCode.MILVUS_ERROR, "创建集合失败: " + created.getMessage());
        }
        client.createIndex(CreateIndexParam.newBuilder()
                .withCollectionName(name)
                .withFieldName("embedding")
                .withIndexName("embedding_idx")
                .withIndexType(IndexType.AUTOINDEX)
                .withMetricType(MetricType.COSINE)
                .build());
        client.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(name).build());
    }

    private FieldType longField(String name, boolean primaryKey, boolean partitionKey) {
        return FieldType.newBuilder()
                .withName(name)
                .withDataType(DataType.Int64)
                .withPrimaryKey(primaryKey)
                .withAutoID(false)
                .withPartitionKey(partitionKey)
                .build();
    }

    private FieldType varCharField(String name, boolean partitionKey) {
        return FieldType.newBuilder()
                .withName(name)
                .withDataType(DataType.VarChar)
                .withMaxLength(64)
                .withPartitionKey(partitionKey)
                .build();
    }

    /** 分块原文冗余存储（≤2048 字符），检索时免回表 */
    private FieldType contentField() {
        return FieldType.newBuilder()
                .withName("content")
                .withDataType(DataType.VarChar)
                .withMaxLength(2048)
                .build();
    }

    private FieldType vectorField() {
        return FieldType.newBuilder()
                .withName("embedding")
                .withDataType(DataType.FloatVector)
                .withDimension(properties.getEmbeddingDim())
                .build();
    }
}
