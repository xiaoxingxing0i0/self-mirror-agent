package com.mirror.agent.repository;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.UpsertParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * 事件摘要向量库封装（event_vector）。
 * 检索强制携带 user_id 过滤，严格实现用户数据隔离；摘要渐进更新经 upsert 保持单事件单向量。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class EventVectorRepository {

    private static final List<String> OUTPUT_FIELDS =
            List.of("user_id", "last_active_time", "content");

    private final MilvusServiceClient client;
    private final MilvusProperties properties;
    private final MilvusCollectionSupport collectionSupport;

    /** 写入/覆盖事件摘要向量（event_id 为主键，重复写入即更新） */
    public void upsertEventVector(Long eventId, Long userId, LocalDateTime lastActiveTime,
                                  String summary, float[] embedding) {
        collectionSupport.ensureEventCollection();
        long epochSecond = lastActiveTime.atZone(ZoneId.systemDefault()).toEpochSecond();
        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field("event_id", List.of(eventId)),
                new InsertParam.Field("user_id", List.of(userId)),
                new InsertParam.Field("last_active_time", List.of(epochSecond)),
                new InsertParam.Field("content", List.of(truncate(summary))),
                new InsertParam.Field("embedding", List.of(toFloatList(embedding))));
        R<?> response = client.upsert(UpsertParam.newBuilder()
                .withCollectionName(properties.getEventCollection())
                .withFields(fields)
                .build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new BizException(ErrorCode.MILVUS_ERROR, "事件向量写入失败: " + response.getMessage());
        }
        log.debug("事件摘要向量已写入 Milvus: eventId={}", eventId);
    }

    /** 按用户检索相似事件摘要 TopK（事件查重/旧事重提定锚） */
    public List<VectorHit> searchByUser(Long userId, float[] queryVector, int topK) {
        collectionSupport.ensureEventCollection();
        String expr = "user_id == " + userId;
        return collectionSupport.search(properties.getEventCollection(),
                toFloatList(queryVector), topK, expr, OUTPUT_FIELDS);
    }

    private String truncate(String summary) {
        String text = summary == null ? "" : summary;
        return text.length() <= 2000 ? text : text.substring(0, 2000);
    }

    private List<Float> toFloatList(float[] embedding) {
        List<Float> vector = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            vector.add(value);
        }
        return vector;
    }
}
