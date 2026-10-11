package com.mirror.agent.repository;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.MilvusProperties;
import com.mirror.agent.entity.KnowledgeDoc;
import io.milvus.client.MilvusServiceClient;
import io.milvus.param.R;
import io.milvus.param.dml.InsertParam;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 知识库向量封装（knowledge_vector，docs/data_model.md §4.3.2）。
 * 知识库为全局共享数据（非用户私有），按 category 分区。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class KnowledgeVectorRepository {

    private static final List<String> OUTPUT_FIELDS =
            List.of("doc_id", "category", "chunk_index", "content");

    private final MilvusServiceClient client;
    private final MilvusProperties properties;
    private final MilvusCollectionSupport collectionSupport;

    /**
     * 批量写入文档分块向量（知识库新增 / 更新时触发）。
     *
     * @param contents   分块原文（截断至 2000 字符内冗余存储）
     * @param embeddings 分块向量，与 contents 一一对应
     */
    public void insertChunks(KnowledgeDoc doc, List<Long> chunkIds,
                             List<String> contents, List<float[]> embeddings) {
        collectionSupport.ensureKnowledgeCollection();
        if (chunkIds.size() != embeddings.size() || chunkIds.size() != contents.size()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "分块数量与向量/原文数量不一致");
        }
        List<List<Float>> vectors = embeddings.stream().map(this::toFloatList).toList();
        List<String> truncated = contents.stream().map(this::truncate).toList();
        InsertParam insertParam = InsertParam.newBuilder()
                .withCollectionName(properties.getKnowledgeCollection())
                .withFields(List.of(
                        new InsertParam.Field("chunk_id", chunkIds),
                        new InsertParam.Field("doc_id", repeat(doc.getId(), chunkIds.size())),
                        new InsertParam.Field("chunk_index", range(chunkIds.size())),
                        new InsertParam.Field("category", repeat(doc.getCategory(), chunkIds.size())),
                        new InsertParam.Field("content", truncated),
                        new InsertParam.Field("embedding", vectors)))
                .build();
        R<?> response = client.insert(insertParam);
        if (response.getStatus() != R.Status.Success.getCode()) {
            throw new BizException(ErrorCode.MILVUS_ERROR, "知识向量写入失败: " + response.getMessage());
        }
        log.info("知识文档向量化完成: docId={}, chunks={}", doc.getId(), chunkIds.size());
    }

    /**
     * 知识库语义检索，category 为空时检索全部分类。
     */
    public List<VectorHit> search(float[] queryVector, int topK, String category) {
        collectionSupport.ensureKnowledgeCollection();
        String expr = (category == null || category.isBlank())
                ? "" : "category == \"" + category + "\"";
        return collectionSupport.search(properties.getKnowledgeCollection(),
                toFloatList(queryVector), topK, expr, OUTPUT_FIELDS);
    }

    private List<Float> toFloatList(float[] embedding) {
        List<Float> vector = new ArrayList<>(embedding.length);
        for (float value : embedding) {
            vector.add(value);
        }
        return vector;
    }

    private <T> List<T> repeat(T value, int count) {
        List<T> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add(value);
        }
        return list;
    }

    private String truncate(String content) {
        return content.length() <= 2000 ? content : content.substring(0, 2000);
    }

    private List<Long> range(int count) {
        List<Long> list = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            list.add((long) i);
        }
        return list;
    }
}
