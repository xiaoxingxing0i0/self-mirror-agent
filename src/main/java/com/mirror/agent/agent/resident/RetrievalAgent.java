package com.mirror.agent.agent.resident;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Message;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.RetrievalItem;
import com.mirror.agent.message.RetrievalRequest;
import com.mirror.agent.repository.KnowledgeVectorRepository;
import com.mirror.agent.repository.MessageMapper;
import com.mirror.agent.repository.MilvusVectorRepository;
import com.mirror.agent.repository.VectorHit;
import com.mirror.agent.service.VectorArchiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 检索 Agent（docs/agent_definition.md §2.4）。
 * 双数据源：历史对话（message_vector，强制 user_id 隔离）+ RAG 知识库（knowledge_vector）。
 * 统一返回「内容 + 相关性得分 + 元数据」结构。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalAgent {

    private final VectorArchiveService vectorArchiveService;
    private final MilvusVectorRepository messageVectorRepository;
    private final KnowledgeVectorRepository knowledgeVectorRepository;
    private final MessageMapper messageMapper;
    private final AgentProperties agentProperties;

    public AgentResult<List<RetrievalItem>> retrieve(RetrievalRequest request) {
        if (request.getUserId() == null) {
            return AgentResult.fail(ErrorCode.PARAM_MISSING, "检索必须携带 userId（数据隔离）");
        }
        int topK = request.getTopK() != null ? request.getTopK() : agentProperties.getRetrievalTopK();
        try {
            float[] queryVector = vectorArchiveService.embed(request.getQuery());
            List<RetrievalItem> items = switch (request.getScope()) {
                case HISTORY -> searchHistory(request, queryVector, topK);
                case KNOWLEDGE -> searchKnowledge(queryVector, topK, null);
                case ALL -> merge(request, queryVector, topK);
            };
            return AgentResult.ok(items);
        } catch (BizException e) {
            log.error("检索失败: scope={}, userId={}", request.getScope(), request.getUserId(), e);
            return AgentResult.fail(e.getErrorCode(), e.getMessage());
        }
    }

    private List<RetrievalItem> merge(RetrievalRequest request, float[] queryVector, int topK) {
        List<RetrievalItem> merged = new ArrayList<>();
        merged.addAll(searchHistory(request, queryVector, topK));
        merged.addAll(searchKnowledge(queryVector, topK, null));
        return merged.stream()
                .sorted(Comparator.comparingDouble(RetrievalItem::getScore).reversed())
                .limit(topK)
                .toList();
    }

    /** 历史对话：向量召回 TopK → 回表取原文（MySQL 为数据源，保证可溯源） */
    private List<RetrievalItem> searchHistory(RetrievalRequest request, float[] queryVector, int topK) {
        List<VectorHit> hits = messageVectorRepository.searchByUser(request.getUserId(), queryVector, topK);
        if (hits.isEmpty()) {
            return List.of();
        }
        List<Long> messageIds = hits.stream().map(VectorHit::id).toList();
        Map<Long, Message> messageMap = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                        .in(Message::getId, messageIds)).stream()
                .collect(Collectors.toMap(Message::getId, Function.identity()));
        List<RetrievalItem> items = new ArrayList<>();
        for (VectorHit hit : hits) {
            Message message = messageMap.get(hit.id());
            if (message == null || !inTimeRange(message, request)) {
                continue;
            }
            items.add(toHistoryItem(hit, message));
        }
        return items;
    }

    private RetrievalItem toHistoryItem(VectorHit hit, Message message) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("messageId", message.getId());
        metadata.put("conversationId", message.getConversationId());
        metadata.put("createTime", message.getCreateTime().toString());
        metadata.put("role", message.getRole());
        return RetrievalItem.builder()
                .content(message.getContent())
                .score(hit.score())
                .source("message_vector")
                .metadata(metadata)
                .build();
    }

    private boolean inTimeRange(Message message, RetrievalRequest request) {
        LocalDateTime createTime = message.getCreateTime();
        if (request.getStartTime() != null && createTime.isBefore(request.getStartTime())) {
            return false;
        }
        return request.getEndTime() == null || !createTime.isAfter(request.getEndTime());
    }

    /** RAG 知识库：分块原文冗余在向量库中，免回表 */
    private List<RetrievalItem> searchKnowledge(float[] queryVector, int topK, String category) {
        List<VectorHit> hits = knowledgeVectorRepository.search(queryVector, topK, category);
        return hits.stream().map(hit -> {
            Map<String, Object> fields = hit.fields();
            return RetrievalItem.builder()
                    .content(String.valueOf(fields.getOrDefault("content", "")))
                    .score(hit.score())
                    .source("knowledge_vector")
                    .metadata(Map.of(
                            "docId", String.valueOf(fields.get("doc_id")),
                            "category", String.valueOf(fields.getOrDefault("category", "")),
                            "chunkIndex", String.valueOf(fields.getOrDefault("chunk_index", ""))))
                    .build();
        }).toList();
    }

    /** 秒级时间戳转本地时间（向量库标量字段 create_time） */
    public static LocalDateTime toLocalDateTime(long epochSecond) {
        return Instant.ofEpochSecond(epochSecond).atZone(ZoneId.systemDefault()).toLocalDateTime();
    }
}
