package com.mirror.agent.repository;

import java.util.Map;

/**
 * Milvus 向量检索命中结果。
 *
 * @param id     主键（message_id / chunk_id）
 * @param score  相关性得分（COSINE，越大越相关）
 * @param fields 输出标量字段
 */
public record VectorHit(long id, float score, Map<String, Object> fields) {
}
