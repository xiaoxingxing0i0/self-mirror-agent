package com.mirror.agent.service;

import com.mirror.agent.entity.Message;
import com.mirror.agent.repository.MilvusVectorRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * 对话向量归档服务：消息落库后异步生成向量写入 Milvus
 * （对话写入链路第 5 步，docs/data_model.md §4.4-1）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VectorArchiveService {

    private final EmbeddingModel embeddingModel;
    private final MilvusVectorRepository messageVectorRepository;

    /** 异步向量化归档，失败仅记录日志（不影响对话主链路，可离线补偿） */
    @Async
    public void archiveMessage(Message message) {
        try {
            float[] embedding = embeddingModel.embed(message.getContent());
            messageVectorRepository.insertMessageVector(message.getId(), message.getUserId(),
                    message.getConversationId(), message.getCreateTime(), embedding);
        } catch (Exception e) {
            log.error("消息向量归档失败: messageId={}", message.getId(), e);
        }
    }

    /** 文本向量化（检索 Agent 查询向量化复用） */
    public float[] embed(String text) {
        return embeddingModel.embed(text);
    }
}
