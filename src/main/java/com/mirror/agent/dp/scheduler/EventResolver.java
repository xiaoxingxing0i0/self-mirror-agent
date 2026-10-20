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
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 事件解析编排器（事件链路 v2，异步消费语言分析 events[]，不占对话响应时延）。
 *
 * <p>分流：refEventId 直连（CONTINUE / AMBIGUOUS——merge-bias 拿不准先归入并落 ambiguous 标记，
 * 封稿时复核可拆分）→ NEW 走向量查重三分区（高置信归并 / 灰区 LLM 裁决 / 新建）。
 * 事实层只追加不改写：现状摘要由定稿器对本事件全部 facts 一次性归并生成。
 * 情绪归因走挂起队列（{@link TagService#bindPendingEmotions}，确定性规则，替代模型回溯指认）。
 * 封稿多条件：超时（{@code eventFinalizeMinutes} 静默）、提及数达上限强制、
 * open 草稿总数超上限强制封最旧；用户纠正信号（{@link CorrectionDetector}）解绑近期存疑归因。
 * 任何异常仅记录日志；Milvus 故障时查重跳过、按新事件落库（可离线补偿合并）。</p>
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
    /** 灰区多候选串行裁决上限（控制单轮 LLM 裁决调用量） */
    private static final int MAX_GRAY_JUDGEMENTS = 3;
    /** 纠正信号触发解绑时，回看存疑提及的时间范围 = 挂起窗口 × 该倍数 */
    private static final int CORRECTION_LOOKBACK_MULTIPLIER = 4;
    /** 单次纠正最多解绑的存疑提及数（防一句「搞错了」大面积拆归因） */
    private static final int CORRECTION_UNBIND_LIMIT = 3;

    private final AgentProperties agentProperties;
    private final EventService eventService;
    private final EventVectorService vectorService;
    private final EventFinalizeQueue finalizeQueue;
    private final EventAgent eventAgent;
    private final AgentInvoker invoker;
    private final TagService tagService;
    private final CorrectionDetector correctionDetector;

    /** 查重命中候选：向量相似度（三分区判定用）+ rerank 分（排序用） */
    private record MatchCandidate(Event event, double similarity, double rerankScore) {
    }

    @Async
    public void resolveAsync(Long userId, Long conversationId, List<Long> turnMessageIds,
                             String inputText, LanguageAnalysisResult analysis) {
        if (!agentProperties.isEventPipelineEnabled() || analysis == null
                || analysis.getEvents() == null || analysis.getEvents().isEmpty()
                || turnMessageIds == null || turnMessageIds.isEmpty()) {
            return;
        }
        try {
            if (correctionDetector.isCorrection(inputText)) {
                unbindRecentAmbiguousAttribution(userId);
            }
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
        boolean ambiguous = EventDraft.ACTION_AMBIGUOUS.equalsIgnoreCase(draft.getEventAction());
        if (draft.getRefEventId() != null
                && !EventDraft.ACTION_NEW.equalsIgnoreCase(draft.getEventAction())) {
            Event event = eventService.findOwned(draft.getRefEventId(), userId);
            if (event != null) {
                attachKnown(userId, event, draft, conversationId, turnMessageIds, ambiguous);
                return;
            }
            log.debug("模型指认的事件 ID 无效，转向量查重: refEventId={}", draft.getRefEventId());
        }
        // 查重三分区（rerank 序单循环）：similarity ≥ high 直接归并；
        // 灰区候选逐个 LLM 裁决（≤MAX_GRAY_JUDGEMENTS 次，旧事重提常伴多个近似候选，
        // 首个 same=true 即归并）；全部落空 → 新建。排序键是 rerank 非相似度，低分候选跳过不中断。
        int judged = 0;
        for (MatchCandidate candidate : dedupCandidates(userId, draft)) {
            if (candidate.similarity() >= agentProperties.getEventDedupHighScore()) {
                attachKnown(userId, candidate.event(), draft, conversationId, turnMessageIds, false);
                return;
            }
            if (candidate.similarity() >= agentProperties.getEventDedupLowScore()) {
                if (judged >= MAX_GRAY_JUDGEMENTS) {
                    continue;
                }
                judged++;
                if (judgeSameEvent(candidate.event(), draft)) {
                    attachKnown(userId, candidate.event(), draft, conversationId, turnMessageIds, false);
                    return;
                }
            }
        }
        createNewEvent(userId, draft, conversationId, turnMessageIds);
    }

    /**
     * 查重多路召回（摘要路 ∪ 事实路）→ 按事件取 max 相似度 → rerank（0.7·相似度
     * + 0.3·时间衰减 + 近期提及加成）降序。异常返回空列表表示降级（按新事件）。
     */
    private List<MatchCandidate> dedupCandidates(Long userId, EventDraft draft) {
        try {
            Map<Long, Double> byEvent = vectorService.searchSimilarMerged(userId,
                    draft.queryText(), agentProperties.getEventSearchTopk());
            if (byEvent.isEmpty()) {
                return List.of();
            }
            Map<Long, Event> eventsById = eventService
                    .listByIds(new ArrayList<>(byEvent.keySet())).stream()
                    .collect(Collectors.toMap(Event::getId, Function.identity()));
            Set<Long> recentIds = eventService
                    .findRecentEvents(userId, agentProperties.getEventContextCount()).stream()
                    .map(Event::getId).collect(Collectors.toSet());
            List<MatchCandidate> candidates = new ArrayList<>();
            for (Map.Entry<Long, Double> entry : byEvent.entrySet()) {
                Event event = eventsById.get(entry.getKey());
                if (event == null) {
                    continue;
                }
                double similarity = entry.getValue();
                double decay = Math.exp(-daysSince(event.getLastActiveTime())
                        / agentProperties.getEventTimeDecayTauDays());
                double rerank = SIMILARITY_WEIGHT * similarity + TIME_DECAY_WEIGHT * decay
                        + (recentIds.contains(event.getId()) ? RECENT_MENTION_BOOST : 0);
                candidates.add(new MatchCandidate(event, similarity, rerank));
            }
            candidates.sort(Comparator.comparingDouble(MatchCandidate::rerankScore).reversed());
            return candidates;
        } catch (Exception e) {
            log.warn("事件查重检索失败，按新事件处理（向量可离线补偿）: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 归入既有事件（延续/重提/存疑归入）：追加事实层 mention（不改写 summary）、
     * 落引用边、情绪归因 + 挂起队列绑定、封稿调度。
     * SETTLED 事件入队即摘要刷新（定稿器负责重生成）。
     */
    private void attachKnown(Long userId, Event event, EventDraft draft, Long conversationId,
                             List<Long> turnMessageIds, boolean ambiguous) {
        int mentionType = isRekindled(event)
                ? EventMention.TYPE_REKINDLED : EventMention.TYPE_CONTINUATION;
        boolean appended = eventService.appendMention(event, draft, mentionType,
                ambiguous ? EventMention.AMBIGUOUS_YES : EventMention.AMBIGUOUS_NO,
                conversationId, turnMessageIds);
        if (appended) {
            vectorService.indexFactsAsync(event.getId(), event.getUserId(),
                    turnMessageIds.get(turnMessageIds.size() - 1), draft.getFacts());
        }
        if (ambiguous) {
            log.info("merge-bias 存疑归入（封稿复核）: eventId={}, messageId={}",
                    event.getId(), turnMessageIds.get(turnMessageIds.size() - 1));
        }
        eventService.saveRelations(userId, event.getId(),
                turnMessageIds.get(turnMessageIds.size() - 1), draft.getReferencedEvents());
        linkTagsAndBindPending(userId, turnMessageIds, event.getId(), draft);
        scheduleFinalize(event);
    }

    private void createNewEvent(Long userId, EventDraft draft, Long conversationId,
                                 List<Long> turnMessageIds) {
        Event event = eventService.createEvent(userId, draft, conversationId, turnMessageIds);
        eventService.saveRelations(userId, event.getId(),
                turnMessageIds.get(turnMessageIds.size() - 1), draft.getReferencedEvents());
        linkTagsAndBindPending(userId, turnMessageIds, event.getId(), draft);
        vectorService.upsertAsync(event);
        vectorService.indexFactsAsync(event.getId(), userId,
                turnMessageIds.get(turnMessageIds.size() - 1), draft.getFacts());
        scheduleFinalize(event);
        enforceMaxOpenDrafts(userId);
    }

    /** 封稿调度：提及数达上限立即强制封稿，否则静默 eventFinalizeMinutes 后封稿/刷新 */
    private void scheduleFinalize(Event event) {
        if (event.getMentionCount() >= agentProperties.getEventSealMaxMentions()) {
            log.info("事件提及数达上限，强制封稿: eventId={}, mentions={}",
                    event.getId(), event.getMentionCount());
            finalizeQueue.touch(event.getId(), 0);
            return;
        }
        finalizeQueue.touch(event.getId(), agentProperties.getEventFinalizeMinutes());
    }

    /** open 草稿总量护栏：EMERGING 超过上限时强制封稿最久未活跃的事件（含本轮新建的自身） */
    private void enforceMaxOpenDrafts(Long userId) {
        try {
            List<Event> open = eventService.findOpenEmergingOldestFirst(userId);
            int excess = open.size() - agentProperties.getEventMaxOpenDrafts();
            for (int i = 0; i < excess; i++) {
                log.info("EMERGING 事件超过上限，强制封稿最旧: eventId={}, name={}",
                        open.get(i).getId(), open.get(i).getName());
                finalizeQueue.touch(open.get(i).getId(), 0);
            }
        } catch (Exception e) {
            log.warn("open 草稿数量护栏执行失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * tag_record 事件归因 + 情绪挂起队列绑定（异步旁路，任何失败只记日志）：
     * ① 本轮消息中命中本事件情绪/心理码的标签行 → event_id；
     * ② 挂起队列：窗口内「先说情绪未说事由」的未归因行按确定性规则绑定本事件
     *    （情绪码交集优先，无交集仅绑最近且最强一条；过期未绑=行仍 NULL，天然可查）。
     */
    private void linkTagsAndBindPending(Long userId, List<Long> turnMessageIds, Long eventId,
                                        EventDraft draft) {
        try {
            List<String> codes = new ArrayList<>(draft.getEmotionCodes());
            codes.addAll(draft.getPsychologyCodes());
            tagService.fillTagsByCodes(userId, turnMessageIds, eventId, codes);
            int bound = tagService.bindPendingEmotions(userId, eventId, codes, turnMessageIds,
                    agentProperties.getEmotionPendingWindowMinutes());
            if (bound > 0) {
                log.info("情绪挂起队列绑定完成: eventId={}, 归因行数={}", eventId, bound);
            }
        } catch (Exception e) {
            log.warn("标签事件归因/挂起绑定失败（不影响事件主数据）: eventId={}", eventId, e);
        }
    }

    /** 纠正信号：把近期存疑归入的情绪/心理归因放回挂起队列（只松动 ambiguous 行，正常归因不动） */
    private void unbindRecentAmbiguousAttribution(Long userId) {
        try {
            LocalDateTime since = LocalDateTime.now().minusMinutes(
                    (long) agentProperties.getEmotionPendingWindowMinutes()
                            * CORRECTION_LOOKBACK_MULTIPLIER);
            List<EventMention> rows = eventService.findRecentAmbiguousMentions(
                    userId, since, CORRECTION_UNBIND_LIMIT);
            for (EventMention row : rows) {
                int unbound = tagService.unbindEmotionsFromEvent(
                        userId, row.getEventId(), row.getMessageId());
                if (unbound > 0) {
                    log.info("用户纠正信号，存疑归因已解绑: eventId={}, messageId={}, rows={}",
                            row.getEventId(), row.getMessageId(), unbound);
                }
            }
        } catch (Exception e) {
            log.warn("纠正解绑存疑归因失败（忽略）: userId={}", userId, e);
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
                && draft.getFacts() != null && !draft.getFacts().isEmpty();
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
        return "名称：" + draft.getName() + "；事实：" + String.join("；", draft.getFacts())
                + "；核心要素：" + draft.getCoreFacts();
    }
}
