package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.IncidentAgent;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Incident;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.IncidentSummaryResult;
import com.mirror.agent.service.IncidentService;
import com.mirror.agent.service.IncidentVectorService;
import com.mirror.agent.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Collectors;

/**
 * 事由草稿定稿轮询器（事由链路 v2）：30s 扫描定稿队列，原子认领后处理。
 *
 * <p>EMERGING 成员 → 封稿：先复核存疑归入（{@link IncidentMutationService}，拆分后重载），
 * 再对本事由全部增量事实一次性归并生成现状摘要（取代逐轮重写，摘要句句可溯源），
 * 挂起队列中未关联的窗口情绪作为 pre_event_emotion 谨慎提示注入 → EMERGING→SETTLED → 刷新向量。
 * SETTLED 成员 → 刷新：封稿后又新增提及（CONTINUATION/REKINDLED），
 * 摘要按完整 facts 重新生成一次（派生缓存重建，状态不变）。</p>
 *
 * <p>摘要生成失败保留 bootstrap（建档时的「名称+事实」拼接，本身即事实级摘要），不阻断定稿。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentDraftFinalizer {

    private static final int BATCH_LIMIT = 50;

    private final AgentProperties agentProperties;
    private final IncidentFinalizeQueue finalizeQueue;
    private final IncidentService incidentService;
    private final IncidentVectorService vectorService;
    private final IncidentMutationService mutationService;
    private final IncidentAgent incidentAgent;
    private final TagService tagService;

    @Scheduled(fixedDelay = 30000)
    public void finalizeDueIncidents() {
        if (!agentProperties.isIncidentPipelineEnabled()) {
            return;
        }
        for (String member : finalizeQueue.due(BATCH_LIMIT)) {
            long incidentId = Long.parseLong(member);
            if (!finalizeQueue.claim(incidentId)) {
                continue;
            }
            try {
                Incident incident = incidentService.load(incidentId);
                if (incident == null) {
                    continue;
                }
                if (incident.getStatus() == Incident.STATUS_EMERGING) {
                    settleIncident(incident);
                } else {
                    refreshSettled(incident);
                }
            } catch (Exception e) {
                log.error("事由定稿失败: incidentId={}", incidentId, e);
            }
        }
    }

    /** 封稿：存疑归入复核拆分 → 事实归并生成现状摘要 + 场景判定 → 状态翻转 + 定稿版向量 */
    private void settleIncident(Incident incident) {
        if (mutationService.reviewAmbiguousAttachments(incident)) {
            Incident reloaded = incidentService.load(incident.getId());
            if (reloaded == null) {
                return;
            }
            incident = reloaded;
        }
        IncidentSummaryResult generated = generateSummary(incident);
        String summary = generated == null ? null : generated.getSummary();
        String scene = generated == null ? null : generated.getScene();
        Incident settled = incidentService.finalizeIncident(incident.getId(), summary, scene);
        if (settled != null) {
            vectorService.upsertQuietly(settled);
            log.debug("事由已封稿: incidentId={}, name={}, 摘要重生成={}, scene={}",
                    settled.getId(), settled.getName(), summary != null, scene);
        }
    }

    /** 已定稿事由刷新：新事实追加后按完整 facts 重新生成派生摘要（含场景）并刷向量 */
    private void refreshSettled(Incident incident) {
        IncidentSummaryResult generated = generateSummary(incident);
        if (generated == null) {
            return;
        }
        incidentService.refreshSummary(incident.getId(), generated.getSummary(), generated.getScene());
        Incident fresh = incidentService.load(incident.getId());
        if (fresh != null) {
            vectorService.upsertQuietly(fresh);
        }
        log.debug("已定稿事由现状摘要已刷新: incidentId={}, scene={}", incident.getId(), generated.getScene());
    }

    /**
     * 事实列表 → 现状摘要 + 场景分类（LLM 一次性归并判定）。
     *
     * @return 生成结果；facts 为空或 LLM 降级返回 null（调用方保留 bootstrap/原分类）
     */
    private IncidentSummaryResult generateSummary(Incident incident) {
        List<String> facts = incidentService.collectFacts(incident.getId());
        if (facts.isEmpty()) {
            return null;
        }
        String factsText = IntStream.range(0, facts.size())
                .mapToObj(i -> (i + 1) + ". " + facts.get(i))
                .collect(Collectors.joining("\n"));
        AgentResult<IncidentSummaryResult> result = incidentAgent.summarizeIncident(
                incident.getName(), factsText, incident.getCoreFacts(), renderPendingEmotionHint(incident));
        return result.getData();
    }

    /** pre_event_emotion 提示：事由叙述窗口内仍未归因的情绪（用户先说情绪后说事由但队列未绑定成功） */
    private String renderPendingEmotionHint(Incident incident) {
        try {
            if (incident.getFirstMentionedAt() == null || incident.getLastMentionedAt() == null) {
                return "";
            }
            LocalDateTime from = incident.getFirstMentionedAt()
                    .minusMinutes(agentProperties.getEmotionPendingWindowMinutes());
            List<String> names = tagService.listPendingEmotionNames(
                    incident.getUserId(), from, incident.getLastMentionedAt());
            if (names.isEmpty()) {
                return "";
            }
            return "用户在叙述本事由前后还表达过「" + String.join("、", names)
                    + "」，尚未与任何事由关联，可能相关但未确认";
        } catch (Exception e) {
            log.debug("挂起情绪提示构建失败（忽略）: incidentId={}", incident.getId());
            return "";
        }
    }
}
