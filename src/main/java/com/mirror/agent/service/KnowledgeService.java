package com.mirror.agent.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.entity.KnowledgeDoc;
import com.mirror.agent.repository.KnowledgeDocMapper;
import com.mirror.agent.repository.KnowledgeVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 知识库服务：文档管理 + 分块向量化（知识库按 200-500 token 分块，
 * 中文按约 300 字分块、50 字重叠，docs/agent_implementation.md §3.4）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeService {

    private static final int CHUNK_SIZE = 300;
    private static final int CHUNK_OVERLAP = 50;

    private final KnowledgeDocMapper docMapper;
    private final KnowledgeVectorRepository knowledgeVectorRepository;
    private final EmbeddingModel embeddingModel;

    /** 应用启动后异步向量化未处理的种子文档（Milvus 未就绪时仅告警） */
    @Async
    @EventListener(ApplicationReadyEvent.class)
    public void vectorizePendingOnStartup() {
        try {
            vectorizePending();
        } catch (Exception e) {
            log.warn("启动时知识库向量化失败，可稍后调用 POST /api/knowledge/vectorize 重试: {}", e.getMessage());
        }
    }

    public int vectorizePending() {
        List<KnowledgeDoc> pending = docMapper.selectList(Wrappers.<KnowledgeDoc>lambdaQuery()
                .eq(KnowledgeDoc::getStatus, KnowledgeDoc.STATUS_PENDING));
        pending.forEach(this::vectorize);
        return pending.size();
    }

    public void vectorize(Long docId) {
        KnowledgeDoc doc = docMapper.selectById(docId);
        if (doc == null) {
            throw new BizException(ErrorCode.KNOWLEDGE_DOC_NOT_FOUND);
        }
        vectorize(doc);
    }

    private void vectorize(KnowledgeDoc doc) {
        List<String> chunks = splitChunks(doc.getContent());
        List<Long> chunkIds = new ArrayList<>(chunks.size());
        List<float[]> embeddings = new ArrayList<>(chunks.size());
        for (String chunk : chunks) {
            chunkIds.add(IdWorker.getId());
            embeddings.add(embeddingModel.embed(chunk));
        }
        knowledgeVectorRepository.insertChunks(doc, chunkIds, chunks, embeddings);
        doc.setChunkCount(chunks.size());
        doc.setStatus(KnowledgeDoc.STATUS_VECTORIZED);
        doc.setUpdateTime(LocalDateTime.now());
        docMapper.updateById(doc);
        log.info("知识文档向量化完成: id={}, title={}", doc.getId(), doc.getTitle());
    }

    public KnowledgeDoc addDoc(String title, String category, String content) {
        KnowledgeDoc doc = new KnowledgeDoc();
        doc.setTitle(title);
        doc.setCategory(category);
        doc.setContent(content);
        doc.setChunkCount(0);
        doc.setStatus(KnowledgeDoc.STATUS_PENDING);
        doc.setCreateTime(LocalDateTime.now());
        doc.setUpdateTime(LocalDateTime.now());
        docMapper.insert(doc);
        return doc;
    }

    public List<KnowledgeDoc> listDocs() {
        return docMapper.selectList(Wrappers.<KnowledgeDoc>lambdaQuery()
                .orderByDesc(KnowledgeDoc::getCreateTime));
    }

    /** 定长滑窗分块（带重叠，避免语义在边界被切断） */
    List<String> splitChunks(String content) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return chunks;
        }
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + CHUNK_SIZE, content.length());
            chunks.add(content.substring(start, end));
            if (end == content.length()) {
                break;
            }
            start = end - CHUNK_OVERLAP;
        }
        return chunks;
    }
}
