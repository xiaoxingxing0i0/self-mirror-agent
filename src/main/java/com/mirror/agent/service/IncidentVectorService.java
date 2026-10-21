package com.mirror.agent.service;

import com.mirror.agent.entity.Incident;
import com.mirror.agent.repository.IncidentVectorRepository;
import com.mirror.agent.repository.VectorHit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 事由向量服务：查重检索（同步多路召回）与向量写入（异步，失败不影响主链路）。
 * 双索引：incident_vector（摘要单向量，封稿刷新版）+ incident_fact_vector（一事实一向量，
 * append-only；旧事重提措辞漂移时由事实路兜住召回）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentVectorService {

    private final VectorArchiveService vectorArchiveService;
    private final IncidentVectorRepository incidentVectorRepository;

    /**
     * 查重多路召回：摘要路 ∪ 事实路，按事由取 max 余弦相似度（一次 embedding 两路共用）。
     * 摘要路失败上抛（调用方按新事由降级）；事实路失败静默退化为仅摘要路。
     */
    public Map<Long, Double> searchSimilarMerged(Long userId, String queryText, int topK) {
        float[] embedding = vectorArchiveService.embed(queryText);
        Map<Long, Double> best = new HashMap<>();
        for (VectorHit hit : incidentVectorRepository.searchByUser(userId, embedding, topK)) {
            best.merge(hit.id(), (double) hit.score(), Math::max);
        }
        try {
            for (VectorHit hit : incidentVectorRepository.searchFactsByUser(userId, embedding, topK)) {
                if (hit.fields().get("incident_id") instanceof Number incidentId) {
                    best.merge(incidentId.longValue(), (double) hit.score(), Math::max);
                }
            }
        } catch (Exception e) {
            log.warn("事实路召回失败，仅用摘要路结果: {}", e.getMessage());
        }
        return best;
    }

    /** 增量事实异步建档（一事实一向量；确定性主键，重试幂等；单行失败跳过可离线补写） */
    @Async
    public void indexFactsAsync(Long incidentId, Long userId, Long messageId, List<String> facts) {
        indexFacts(incidentId, userId, messageId, facts);
    }

    /** 事实索引写入（同步执行体；主键 = hash(incidentId, messageId, index)，与删除端复算一致） */
    public void indexFacts(Long incidentId, Long userId, Long messageId, List<String> facts) {
        if (facts == null || facts.isEmpty() || messageId == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < facts.size(); i++) {
            String fact = facts.get(i);
            if (fact == null || fact.isBlank()) {
                continue;
            }
            try {
                float[] embedding = vectorArchiveService.embed(fact);
                incidentVectorRepository.upsertFactVector(
                        IncidentVectorRepository.factVectorId(incidentId, messageId, i),
                        incidentId, userId, messageId, now, fact, embedding);
            } catch (Exception e) {
                log.warn("事实向量写入失败（跳过，可离线补写）: incidentId={}, index={}, err={}",
                        incidentId, i, e.getMessage());
            }
        }
    }

    /**
     * 拆分通道事实向量迁移：按源事由复算旧主键删除，再以新事由主键重建（少量行，重新 embed）。
     * 删除失败仅残留旧归属行（召回噪声由灰区裁决自愈），告警不阻断。
     */
    @Async
    public void moveFacts(Long fromIncidentId, Long toIncidentId, Long userId,
                          Map<Long, List<String>> messageFacts) {
        List<Long> oldIds = new ArrayList<>();
        for (Map.Entry<Long, List<String>> entry : messageFacts.entrySet()) {
            List<String> facts = entry.getValue();
            for (int i = 0; i < facts.size(); i++) {
                oldIds.add(IncidentVectorRepository.factVectorId(fromIncidentId, entry.getKey(), i));
            }
            indexFacts(toIncidentId, userId, entry.getKey(), facts);
        }
        try {
            incidentVectorRepository.deleteFacts(oldIds);
        } catch (Exception e) {
            log.warn("拆分后旧事实向量行删除失败（残留待清理）: fromIncidentId={}, err={}",
                    fromIncidentId, e.getMessage());
        }
    }

    /** 异步写入/覆盖事由摘要向量（建档、摘要实质变化、定稿时调用；场景分类随 entity 透传） */
    @Async
    public void upsertAsync(Incident incident) {
        try {
            float[] embedding = vectorArchiveService.embed(incident.getSummary());
            incidentVectorRepository.upsertIncidentVector(incident.getId(), incident.getUserId(),
                    incident.getLastActiveTime(), incident.getSummary(), embedding, incident.getSceneCategory());
        } catch (Exception e) {
            log.error("事由向量写入失败（MySQL 已落库，向量可离线补偿）: incidentId={}", incident.getId(), e);
        }
    }

    /** 同步向量写入（定稿场景）；失败仅告警 */
    public void upsertQuietly(Incident incident) {
        try {
            float[] embedding = vectorArchiveService.embed(incident.getSummary());
            incidentVectorRepository.upsertIncidentVector(incident.getId(), incident.getUserId(),
                    incident.getLastActiveTime(), incident.getSummary(), embedding, incident.getSceneCategory());
        } catch (Exception e) {
            log.warn("事由定稿向量刷新失败: incidentId={}, reason={}", incident.getId(), e.getMessage());
        }
    }
}
