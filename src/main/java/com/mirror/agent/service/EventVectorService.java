package com.mirror.agent.service;

import com.mirror.agent.entity.Event;
import com.mirror.agent.repository.EventVectorRepository;
import com.mirror.agent.repository.VectorHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 事件摘要向量服务：查重检索（同步）与向量写入（异步，失败不影响主链路）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventVectorService {

    private final VectorArchiveService vectorArchiveService;
    private final EventVectorRepository eventVectorRepository;

    /** 按事件名+核心要素检索相似既有事件（摘要↔摘要同分布检索） */
    public List<VectorHit> searchSimilar(Long userId, String queryText, int topK) {
        float[] embedding = vectorArchiveService.embed(queryText);
        return eventVectorRepository.searchByUser(userId, embedding, topK);
    }

    /** 异步写入/覆盖事件摘要向量（建档、摘要实质变化、定稿时调用） */
    @Async
    public void upsertAsync(Event event) {
        try {
            float[] embedding = vectorArchiveService.embed(event.getSummary());
            eventVectorRepository.upsertEventVector(event.getId(), event.getUserId(),
                    event.getLastActiveTime(), event.getSummary(), embedding);
        } catch (Exception e) {
            log.error("事件向量写入失败（MySQL 已落库，向量可离线补偿）: eventId={}", event.getId(), e);
        }
    }

    /** 同步向量写入（定稿场景）；失败仅告警 */
    public void upsertQuietly(Event event) {
        try {
            float[] embedding = vectorArchiveService.embed(event.getSummary());
            eventVectorRepository.upsertEventVector(event.getId(), event.getUserId(),
                    event.getLastActiveTime(), event.getSummary(), embedding);
        } catch (Exception e) {
            log.warn("事件定稿向量刷新失败: eventId={}, reason={}", event.getId(), e.getMessage());
        }
    }
}
