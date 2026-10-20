package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.EventAgent;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Event;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.EventSummaryResult;
import com.mirror.agent.service.EventService;
import com.mirror.agent.service.EventVectorService;
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
 * 事件草稿定稿轮询器（事件链路 v2）：30s 扫描定稿队列，原子认领后处理。
 *
 * <p>EMERGING 成员 → 封稿：先复核存疑归入（{@link EventMutationService}，拆分后重载），
 * 再对本事件全部增量事实一次性归并生成现状摘要（取代逐轮重写，摘要句句可溯源），
 * 挂起队列中未关联的窗口情绪作为 pre_event_emotion 谨慎提示注入 → EMERGING→SETTLED → 刷新向量。
 * SETTLED 成员 → 刷新：封稿后又新增提及（CONTINUATION/REKINDLED），
 * 摘要按完整 facts 重新生成一次（派生缓存重建，状态不变）。</p>
 *
 * <p>摘要生成失败保留 bootstrap（建档时的「名称+事实」拼接，本身即事实级摘要），不阻断定稿。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventDraftFinalizer {

    private static final int BATCH_LIMIT = 50;

    private final AgentProperties agentProperties;
    private final EventFinalizeQueue finalizeQueue;
    private final EventService eventService;
    private final EventVectorService vectorService;
    private final EventMutationService mutationService;
    private final EventAgent eventAgent;
    private final TagService tagService;

    @Scheduled(fixedDelay = 30000)
    public void finalizeDueEvents() {
        if (!agentProperties.isEventPipelineEnabled()) {
            return;
        }
        for (String member : finalizeQueue.due(BATCH_LIMIT)) {
            long eventId = Long.parseLong(member);
            if (!finalizeQueue.claim(eventId)) {
                continue;
            }
            try {
                Event event = eventService.load(eventId);
                if (event == null) {
                    continue;
                }
                if (event.getStatus() == Event.STATUS_EMERGING) {
                    settleEvent(event);
                } else {
                    refreshSettled(event);
                }
            } catch (Exception e) {
                log.error("事件定稿失败: eventId={}", eventId, e);
            }
        }
    }

    /** 封稿：存疑归入复核拆分 → 事实归并生成现状摘要 → 状态翻转 + 定稿版向量 */
    private void settleEvent(Event event) {
        if (mutationService.reviewAmbiguousAttachments(event)) {
            Event reloaded = eventService.load(event.getId());
            if (reloaded == null) {
                return;
            }
            event = reloaded;
        }
        String summary = generateSummary(event);
        Event settled = eventService.finalizeEvent(event.getId(), summary);
        if (settled != null) {
            vectorService.upsertQuietly(settled);
            log.debug("事件已封稿: eventId={}, name={}, 摘要重生成={}",
                    settled.getId(), settled.getName(), summary != null);
        }
    }

    /** 已定稿事件刷新：新事实追加后按完整 facts 重新生成派生摘要并刷向量 */
    private void refreshSettled(Event event) {
        String summary = generateSummary(event);
        if (summary == null) {
            return;
        }
        eventService.refreshSummary(event.getId(), summary);
        Event fresh = eventService.load(event.getId());
        if (fresh != null) {
            vectorService.upsertQuietly(fresh);
        }
        log.debug("已定稿事件现状摘要已刷新: eventId={}", event.getId());
    }

    /** 事实列表 → 现状摘要（LLM 一次性归并；失败返回 null 由调用方保留 bootstrap） */
    private String generateSummary(Event event) {
        List<String> facts = eventService.collectFacts(event.getId());
        if (facts.isEmpty()) {
            return null;
        }
        String factsText = IntStream.range(0, facts.size())
                .mapToObj(i -> (i + 1) + ". " + facts.get(i))
                .collect(Collectors.joining("\n"));
        AgentResult<EventSummaryResult> result = eventAgent.summarizeEvent(
                event.getName(), factsText, event.getCoreFacts(), renderPendingEmotionHint(event));
        return result.getData() == null ? null : result.getData().getSummary();
    }

    /** pre_event_emotion 提示：事件叙述窗口内仍未归因的情绪（用户先说情绪后说事由但队列未绑定成功） */
    private String renderPendingEmotionHint(Event event) {
        try {
            if (event.getFirstMentionedAt() == null || event.getLastMentionedAt() == null) {
                return "";
            }
            LocalDateTime from = event.getFirstMentionedAt()
                    .minusMinutes(agentProperties.getEmotionPendingWindowMinutes());
            List<String> names = tagService.listPendingEmotionNames(
                    event.getUserId(), from, event.getLastMentionedAt());
            if (names.isEmpty()) {
                return "";
            }
            return "用户在叙述本事件前后还表达过「" + String.join("、", names)
                    + "」，尚未与任何事件关联，可能相关但未确认";
        } catch (Exception e) {
            log.debug("挂起情绪提示构建失败（忽略）: eventId={}", event.getId());
            return "";
        }
    }
}
