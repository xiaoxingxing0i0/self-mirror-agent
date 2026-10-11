package com.mirror.agent.repository;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 对话向量库封装（message_vector，docs/data_model.md §4.3.1）。
 * 检索强制携带 user_id 过滤，严格实现用户数据隔离。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class MilvusVectorRepository {

    private static final List<String> OUTPUT_FIELDS =
            List.of("user_id", "conversation_id", "create_time");

    private final MilvusServiceClient client;
    private final MilvusProperties properties;
    private final MilvusCollectionSupport collectionSupport;

    /**
     * 写入消息向量（对话归档链路异步调用）。
     */
    public void insertMessageVector(Long messageId, Long userId, Long conversationId,
                                    LocalDateTime createTime, float[] embedding) {
        collectionSupport.ensureMessageCollection();
        long epochSecond = createTime.atZone(ZoneId.systemDefault()).toEpochSecond();
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(properties.getMessageCollection())
                .withFields(List.of(
                        new InsertParam.Field("message_id", List.of(messageId)),
                        new InsertParam.Field("user_id", List.of(userId)),
                        new InsertParam.Field("conversation_id", List.of(conversationId)),
                        new InsertParam.Field("create_time", List.of(epochSecond)),
                        new InsertParam.Field("embedding", List.of(toFloatList(embedding)))))
                .build();
        R<?> response = client.insert(insertParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new BizException(ErrorCode.MILVUS_ERROR, "消息向量写入失败: " + response.getMessage());
        }
        log.debug("消息向量已写入 Milvus: messageId={}", messageId);
    }

    /**
     * 按用户语义检索历史对话向量 TopK。
     */
    public List<VectorHit> searchByUser(Long userId, float[] queryVector, int topK) {
        collectionSupport.ensureMessageCollection();
        String expr = "user_id == " + userId;
        return collectionSupport.search(properties.getMessageCollection(),
                toFloatList(queryVector), topK, expr, OUTPUT_FIELDS);
    }

    private List<Float> toFloatList(float[] embedding) {
        List<Float> vector = new java.util.ArrayList<>(embedding.length);
        for (float value : embedding) {
            vector.add(value);
        }
        return vector;
    }
}
