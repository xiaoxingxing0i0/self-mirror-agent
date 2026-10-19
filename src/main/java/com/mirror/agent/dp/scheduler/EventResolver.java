package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.EventAgent;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Event;
import com.mirror.agent.entity.EventMention;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.AgentType;
import com.mirror.agent.message.EventDraft;
import com.mirror.agent.message.EventJudgeResult;
import com.mirror.agent.message.LanguageAnalysisResult;
import com.mirror.agent.repository.VectorHit;
import com.mirror.agent.service.EventService;
import com.mirror.agent.service.EventVectorService;
import com.mirror.agent.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 事件解析编排器：消费语言分析 events[]（异步，不占对话响应时延），
 * 场景2上下文指认（refEventId 直连）→ 场景1/3 向量查重三分区（高置信命中 / 灰区 LLM 裁决 / 新建），
 * 维护事件建档、现状摘要渐进改写、episode 语义提及计数与定稿计时。
 * 任何异常仅记录日志；Milvus 故障时查重跳过、按新事件落库（可离线补偿合并）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventResolver {

    /** rerank 权重：相似度 / 时间衰减 / 近期提及加成（doc/modify 20261015 §6） */
    private static final double SIMILARITY_WEIGHT = 0.7;
    private static final double TIME_DECAY_WEIGHT = 0.3;
    private static final double RECENT_MENTION_BOOST = 0.1;
    /** 单轮最多处理事件数（与 Prompt 输出上限一致） */
    private static final int MAX_EVENTS_PER_TURN = 3;

    private final AgentProperties agentProperties;
    private final EventService eventService;
    private final EventVectorService vectorService;
    private final EventFinalizeQueue finalizeQueue;
    private final EventAgent eventAgent;
    private final AgentInvoker invoker;
    private final TagService tagService;

    /** 查重命中候选：向量相似度（三分区判定用）+ rerank 分（排序用） */
    private record MatchCandidate(Event event, double similarity, double rerankScore) {
    }

    @Async
    public void resolveAsync(Long userId, Long conversationId, List<Long> turnMessageIds,
                             LanguageAnalysisResult analysis) {
        if (!agentProperties.isEventPipelineEnabled() || analysis == null
                || analysis.getEvents() == null || analysis.getEvents().isEmpty()
                || turnMessageIds == null || turnMessageIds.isEmpty()) {
            return;
        }
        try {
            for (EventDraft draft : analysis.getEvents().stream()
                    .filter(this::isValid).limit(MAX_EVENTS_PER_TURN).toList()) {
                resolveOne(userId, conversationId, turnMessageIds, draft);
            }
        } catch (Exception e) {
            log.error("事件链路处理异常（不影响主对话）: userId={}", userId, e);
        }
    }

    private void resolveOne(Long userId, Long conversationId, List<Long> turnMessageIds,
                            EventDraft draft) {
        if (draft.getRefEventId() != null) {
            Event event = eventService.findOwned(draft.getRefEventId(), userId);
            if (event != null) {
                handleKnown(event, draft, conversationId, turnMessageIds);
                return;
            }
            log.debug("模型指认的事件 ID 无效，转向量查重: refEventId={}", draft.getRefEventId());
        }
        MatchCandidate best = dedupBestMatch(userId, draft);
        if (best != null) {
            if (best.similarity() >= agentProperties.getEventDedupHighScore()) {
                handleKnown(best.event(), draft, conversationId, turnMessageIds);
                return;
            }
            if (best.similarity() >= agentProperties.getEventDedupLowScore()
                    && judgeSameEvent(best.event(), draft)) {
                handleKnown(best.event(), draft, conversationId, turnMessageIds);
                return;
            }
        }
        createNewEvent(userId, draft, conversationId, turnMessageIds);
    }

    /** 查重检索 + rerank（0.7·相似度 + 0.3·时间衰减 + 近期提及加成），异常返回 null 表示降级跳过 */
    private MatchCandidate dedupBestMatch(Long userId, EventDraft draft) {
        try {
            List<VectorHit> hits = vectorService.searchSimilar(userId, draft.queryText(),
                    agentProperties.getEventSearchTopk());
            if (hits.isEmpty()) {
                return null;
            }
            Map<Long, Event> eventsById = eventService
                    .listByIds(hits.stream().map(VectorHit::id).toList()).stream()
                    .collect(Collectors.toMap(Event::getId, Function.identity()));
            Set<Long> recentIds = eventService
                    .findRecentEvents(userId, agentProperties.getEventContextCount()).stream()
                    .map(Event::getId).collect(Collectors.toSet());
            MatchCandidate best = null;
            for (VectorHit hit : hits) {
                Event event = eventsById.get(hit.id());
                if (event == null) {
                    continue;
                }
                double decay = Math.exp(-daysSince(event.getLastActiveTime())
                        / agentProperties.getEventTimeDecayTauDays());
                double rerank = SIMILARITY_WEIGHT * hit.score() + TIME_DECAY_WEIGHT * decay
                        + (recentIds.contains(event.getId()) ? RECENT_MENTION_BOOST : 0);
                if (best == null || rerank > best.rerankScore()) {
                    best = new MatchCandidate(event, hit.score(), rerank);
                }
            }
            return best;
        } catch (Exception e) {
            log.warn("事件查重检索失败，按新事件处理（向量可离线补偿）: {}", e.getMessage());
            return null;
        }
    }

    /** 既有事件延续/重提：episode 时间规则定类型，摘要实质变化才刷向量 */
    private void handleKnown(Event event, EventDraft draft, Long conversationId,
                             List<Long> turnMessageIds) {
        int mentionType = isRekindled(event)
                ? EventMention.TYPE_REKINDLED : EventMention.TYPE_CONTINUATION;
        String oldSummary = event.getSummary();
        eventService.applyKnownEvent(event, draft, mentionType, conversationId, turnMessageIds);
        if (event.getStatus() == Event.STATUS_EMERGING) {
            finalizeQueue.touch(event.getId(), agentProperties.getEventFinalizeMinutes());
        }
        if (eventService.summaryMateriallyChanged(oldSummary, event.getSummary())) {
            vectorService.upsertAsync(event);
        }
        linkTagsToEvent(event.getUserId(), turnMessageIds, event.getId(), draft);
    }

    private void createNewEvent(Long userId, EventDraft draft, Long conversationId,
                                List<Long> turnMessageIds) {
        Event event = eventService.createEvent(userId, draft, conversationId, turnMessageIds);
        finalizeQueue.touch(event.getId(), agentProperties.getEventFinalizeMinutes());
        vectorService.upsertAsync(event);
        linkTagsToEvent(userId, turnMessageIds, event.getId(), draft);
    }

    /**
     * tag_record 事件归因回填（异步旁路，任何失败只记日志）：
     * ① 本轮消息中命中本事件情绪/心理码的标签行 → event_id；
     * ② 回溯归因：模型判定「本事件解释了此前只说情绪未说事由」的历史消息（[id=..] 引用）→ event_id，
     *    由 TagService 按 user_id + 未归因 + emotion/psychology 三重条件兜底校验，模型幻觉 id 或越权无效。
     */
    private void linkTagsToEvent(Long userId, List<Long> turnMessageIds, Long eventId,
                                 EventDraft draft) {
        try {
            List<String> codes = new ArrayList<>(draft.getEmotionCodes());
            codes.addAll(draft.getPsychologyCodes());
            tagService.fillTagsByCodes(userId, turnMessageIds, eventId, codes);
            List<Long> backfillIds = draft.getBackfillMessageIds();
            if (backfillIds != null && !backfillIds.isEmpty()) {
                int linked = tagService.backfillTagsToEvent(userId, backfillIds, eventId);
                log.info("情绪消息回溯归因完成: eventId={}, 候选消息={}, 回填行数={}",
                        eventId, backfillIds, linked);
            }
        } catch (Exception e) {
            log.warn("标签事件归因回填失败（不影响事件主数据）: eventId={}", eventId, e);
        }
    }

    /** episode 规则：距上次提及 ≥ 间隔阈值 视为重提，否则为同话题连续 */
    private boolean isRekindled(Event event) {
        LocalDateTime last = event.getLastMentionedAt();
        if (last == null) {
            return true;
        }
        return Duration.between(last, LocalDateTime.now()).toMinutes()
                >= agentProperties.getEventEpisodeGapMinutes();
    }

    private boolean judgeSameEvent(Event existing, EventDraft draft) {
        AgentResult<EventJudgeResult> result = invoker.invoke(AgentType.EVENT, "judge_same_event",
                () -> eventAgent.judgeSameEvent(describeExisting(existing), describeDraft(draft)));
        return result.getData() != null && result.getData().isSame();
    }

    private boolean isValid(EventDraft draft) {
        return draft != null && draft.getName() != null && !draft.getName().isBlank()
                && draft.getSummaryDraft() != null && !draft.getSummaryDraft().isBlank();
    }

    private double daysSince(LocalDateTime time) {
        return Duration.between(time == null ? LocalDateTime.now() : time,
                LocalDateTime.now()).toHours() / 24.0;
    }

    private String describeExisting(Event event) {
        return "名称：" + event.getName() + "；摘要：" + event.getSummary()
                + "；核心要素：" + (event.getCoreFacts() == null ? "（无）" : event.getCoreFacts());
    }

    private String describeDraft(EventDraft draft) {
        return "名称：" + draft.getName() + "；摘要：" + draft.getSummaryDraft()
                + "；核心要素：" + draft.getCoreFacts();
    }
}
