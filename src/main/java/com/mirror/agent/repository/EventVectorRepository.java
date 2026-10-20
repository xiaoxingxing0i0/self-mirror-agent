package com.mirror.agent.repository;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.MilvusProperties;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.dml.DeleteParam;
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

    /** 写入/覆盖单条增量事实向量（fact_id 确定性主键，重复写入即更新，重试幂等） */
    public void upsertFactVector(long factId, long eventId, long userId, long messageId,
                                 LocalDateTime createTime, String content, float[] embedding) {
        collectionSupport.ensureFactCollection();
        List<InsertParam.Field> fields = List.of(
                new InsertParam.Field("fact_id", List.of(factId)),
                new InsertParam.Field("event_id", List.of(eventId)),
                new InsertParam.Field("user_id", List.of(userId)),
                new InsertParam.Field("message_id", List.of(messageId)),
                new InsertParam.Field("create_time", List.of(
                        createTime.atZone(ZoneId.systemDefault()).toEpochSecond())),
                new InsertParam.Field("content", List.of(truncate(content))),
                new InsertParam.Field("embedding", List.of(toFloatList(embedding))));
        R<?> response = client.upsert(UpsertParam.newBuilder()
                .withCollectionName(properties.getFactCollection())
                .withFields(fields)
                .build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new BizException(ErrorCode.MILVUS_ERROR, "事实向量写入失败: " + response.getMessage());
        }
    }

    /** 按用户检索相似事实 TopK（查重多路召回；命中经 fields.event_id 归并到事件） */
    public List<VectorHit> searchFactsByUser(Long userId, float[] queryVector, int topK) {
        collectionSupport.ensureFactCollection();
        String expr = "user_id == " + userId;
        return collectionSupport.search(properties.getFactCollection(),
                toFloatList(queryVector), topK, expr, List.of("event_id", "user_id", "content"));
    }

    /** 按确定性主键删除事实向量（拆分通道迁移旧归属行） */
    public void deleteFacts(List<Long> factIds) {
        if (factIds == null || factIds.isEmpty()) {
            return;
        }
        collectionSupport.ensureFactCollection();
        R<?> response = client.delete(DeleteParam.newBuilder()
                .withCollectionName(properties.getFactCollection())
                .withPrimaryKeys(factIds.stream().map(String::valueOf).toList())
                .build());
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new BizException(ErrorCode.MILVUS_ERROR, "事实向量删除失败: " + response.getMessage());
        }
    }

    /** fact_id 确定性主键：hash(eventId, messageId, index)——重试幂等、拆分可复算 */
    public static long factVectorId(long eventId, long messageId, int index) {
        long hash = eventId;
        hash = hash * 1000003L ^ messageId;
        hash = hash * 31L + index;
        return hash & Long.MAX_VALUE;
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
