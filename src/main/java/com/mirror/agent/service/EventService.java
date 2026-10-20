package com.mirror.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Event;
import com.mirror.agent.entity.EventMention;
import com.mirror.agent.entity.EventRelation;
import com.mirror.agent.message.EventDraft;
import com.mirror.agent.repository.EventMapper;
import com.mirror.agent.repository.EventMentionMapper;
import com.mirror.agent.repository.EventRelationMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 事件 / 提及 / 引用关系的持久化服务（MySQL）+ 上下文注入渲染。
 *
 * <p>事件链路 v2「事实稳定层」：event_mention.fact_content 为只追加的增量事实层，
 * 事件现状摘要（event.summary）在建档时写 bootstrap（名称+初始事实），
 * 封稿时由 {@link #collectFacts} 归并全部事实一次性生成，中途不逐轮改写（防摘要漂移）。
 * event_relation 只存不同事件间的引用（对比/因果/提及）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private static final DateTimeFormatter REF_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 摘要长度上限（与 entity/Event 注释口径一致） */
    private static final int SUMMARY_MAX_CHARS = 300;
    /** 单条增量事实长度上限（与提示词「≤50字」口径一致，冗余放宽到 120 容错） */
    private static final int FACT_MAX_CHARS = 120;
    /** 单事件单次归并的事实条数上限（封稿摘要生成截断，防超长 prompt） */
    private static final int FACTS_COLLECT_LIMIT = 40;
    /** 上下文注入中 EMERGING 事件展示的最近事实条数（增量抽取的对照面） */
    private static final int RECENT_FACTS_PER_EVENT = 3;
    /** 事件档案渲染上限（query 直检：事件数/事实数/时间线/引用边） */
    private static final DateTimeFormatter DOSIER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int DOSIER_MAX_EVENTS = 5;
    private static final int DOSIER_MAX_FACTS = 5;
    private static final int DOSIER_MAX_TIMELINE = 8;
    private static final int DOSIER_MAX_RELATIONS = 10;

    private final EventMapper eventMapper;
    private final EventMentionMapper mentionMapper;
    private final EventRelationMapper relationMapper;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    /** 按 ID 加载事件（定稿器/拆分通道内部使用，归属校验在调用方入口完成） */
    public Event load(Long eventId) {
        return eventId == null ? null : eventMapper.selectById(eventId);
    }

    /** 已定稿事件新增提及后的摘要刷新（派生缓存重建，状态保持 SETTLED） */
    public void refreshSummary(Long eventId, String mergedSummary) {
        Event event = eventMapper.selectById(eventId);
        if (event == null || !hasText(mergedSummary)) {
            return;
        }
        event.setSummary(clip(mergedSummary, SUMMARY_MAX_CHARS));
        eventMapper.updateById(event);
    }

    /** 某事件的存疑归入（ambiguous=1）提及行（封稿复核拆分用） */
    public List<EventMention> findAmbiguousMentions(Long eventId) {
        return mentionMapper.selectList(Wrappers.<EventMention>lambdaQuery()
                .eq(EventMention::getEventId, eventId)
                .eq(EventMention::getAmbiguous, EventMention.AMBIGUOUS_YES)
                .orderByAsc(EventMention::getCreateTime));
    }

    /** 某事件的确定提及行（ambiguous=0 或历史空值），存疑复核的对照面 */
    public List<EventMention> findSolidMentions(Long eventId) {
        return mentionMapper.selectList(Wrappers.<EventMention>lambdaQuery()
                .eq(EventMention::getEventId, eventId)
                .and(w -> w.eq(EventMention::getAmbiguous, EventMention.AMBIGUOUS_NO)
                        .or().isNull(EventMention::getAmbiguous))
                .orderByAsc(EventMention::getCreateTime));
    }

    /** 解析提及行的增量事实 JSON 数组（拆分/摘要素材用） */
    public List<String> readFactsOf(EventMention mention) {
        return readStringList(mention.getFactContent());
    }

    /**
     * 拆分建档：把误归入的存疑提及拆出为独立 EMERGING 草稿。
     * 名称取拆出首条事实截断，摘要=拆出事实拼接，核心要素沿用源事件（拆分后由后续叙述继续修正）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Event createSplitDraft(Event source, List<EventMention> splitRows) {
        LocalDateTime now = LocalDateTime.now();
        List<String> facts = new ArrayList<>();
        for (EventMention row : splitRows) {
            facts.addAll(readFactsOf(row));
        }
        Event event = new Event();
        event.setUserId(source.getUserId());
        String firstName = facts.isEmpty()
                ? source.getName() + "(拆出待确认)" : clip(facts.get(0), 60);
        event.setName(clip(firstName, 60));
        event.setSummary(clip(facts.isEmpty() ? firstName : String.join("；", facts), SUMMARY_MAX_CHARS));
        event.setStatus(Event.STATUS_EMERGING);
        event.setCoreFacts(source.getCoreFacts());
        event.setEmotionProfile(splitRows.get(0).getEmotionCodes());
        event.setMentionCount(splitRows.size());
        event.setRekindleCount(0);
        event.setFirstMentionedAt(splitRows.get(0).getCreateTime());
        event.setLastMentionedAt(splitRows.get(splitRows.size() - 1).getCreateTime());
        event.setLastActiveTime(now);
        eventMapper.insert(event);
        return event;
    }

    /** 拆分连带迁移引用边：源事件中以拆出消息为证据的出边挂到新事件 */
    public void reparentRelations(Long fromEventId, Long toEventId, List<Long> evidenceMessageIds) {
        if (evidenceMessageIds == null || evidenceMessageIds.isEmpty()) {
            return;
        }
        EventRelation patch = new EventRelation();
        patch.setEventId(toEventId);
        relationMapper.update(patch, Wrappers.<EventRelation>lambdaUpdate()
                .eq(EventRelation::getEventId, fromEventId)
                .in(EventRelation::getEvidenceMessageId, evidenceMessageIds));
    }

    /** 查询归属该用户的事件（防越权/防模型幻觉 ID） */
    public Event findOwned(Long eventId, Long userId) {
        if (eventId == null || userId == null) {
            return null;
        }
        return eventMapper.selectOne(Wrappers.<Event>lambdaQuery()
                .eq(Event::getId, eventId).eq(Event::getUserId, userId));
    }

    public List<Event> listByIds(List<Long> eventIds) {
        return eventIds.isEmpty() ? List.of() : eventMapper.selectByIds(eventIds);
    }

    /**
     * 新事件建档（EMERGING）+ 落 NEW 提及（溯源取本轮最后一条消息，合并轮覆盖全部碎片）。
     * summary 写 bootstrap（名称+初始事实拼接），定稿时被事实归并摘要覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    public Event createEvent(Long userId, EventDraft draft, Long conversationId,
                             List<Long> turnMessageIds) {
        Long messageId = turnMessageIds.get(turnMessageIds.size() - 1);
        LocalDateTime now = LocalDateTime.now();
        Event event = new Event();
        event.setUserId(userId);
        event.setName(clip(draft.getName(), 60));
        event.setSummary(bootstrapSummary(draft));
        event.setStatus(Event.STATUS_EMERGING);
        event.setCoreFacts(writeJson(draft.getCoreFacts()));
        event.setEmotionProfile(writeJson(draft.getEmotionCodes()));
        event.setMentionCount(1);
        event.setRekindleCount(0);
        event.setFirstMentionedAt(now);
        event.setLastMentionedAt(now);
        event.setLastActiveTime(now);
        eventMapper.insert(event);
        insertMention(event.getId(), userId, conversationId, messageId,
                EventMention.TYPE_NEW, draft, EventMention.AMBIGUOUS_NO);
        return event;
    }

    /**
     * 既有事件被延续/重提/存疑归入：只追加事实层 mention（不改写 summary），
     * 刷新计数、活跃时间、核心要素与情绪冗余。ambiguous=YES 承载 merge-bias 存疑归入。
     * 幂等：同事件同 message_id 已存在提及则跳过。
     *
     * @return true=本次真实追加（调用方据此写事实向量）；false=重复消费被幂等跳过
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean appendMention(Event event, EventDraft draft, int mentionType, int ambiguous,
                                 Long conversationId, List<Long> turnMessageIds) {
        Long messageId = turnMessageIds.get(turnMessageIds.size() - 1);
        if (mentionExists(event.getId(), messageId)) {
            log.debug("提及已存在，幂等跳过: eventId={}, messageId={}", event.getId(), messageId);
            return false;
        }
        insertMention(event.getId(), event.getUserId(), conversationId, messageId,
                mentionType, draft, ambiguous);
        event.setMentionCount(event.getMentionCount() + 1);
        if (mentionType == EventMention.TYPE_REKINDLED) {
            event.setRekindleCount(event.getRekindleCount() + 1);
        }
        LocalDateTime now = LocalDateTime.now();
        event.setLastMentionedAt(now);
        event.setLastActiveTime(now);
        if (hasText(draft.getName())) {
            event.setName(clip(draft.getName(), 60));
        }
        if (draft.getCoreFacts() != null && !draft.getCoreFacts().isEmpty()) {
            event.setCoreFacts(writeJson(draft.getCoreFacts()));
        }
        if (draft.getEmotionCodes() != null && !draft.getEmotionCodes().isEmpty()) {
            event.setEmotionProfile(writeJson(draft.getEmotionCodes()));
        }
        eventMapper.updateById(event);
        return true;
    }

    /** 草稿到期定稿（EMERGING → SETTLED），summary 替换为事实归并生成的现状摘要 */
    @Transactional(rollbackFor = Exception.class)
    public Event finalizeEvent(Long eventId, String mergedSummary) {
        Event event = eventMapper.selectById(eventId);
        if (event == null || event.getStatus() != Event.STATUS_EMERGING) {
            return null;
        }
        event.setStatus(Event.STATUS_SETTLED);
        if (hasText(mergedSummary)) {
            event.setSummary(clip(mergedSummary, SUMMARY_MAX_CHARS));
        }
        eventMapper.updateById(event);
        return event;
    }

    /**
     * 收集事件全部增量事实（按提及时间升序，幂等去重后），供封稿摘要生成。
     * 无事实时回退 summary（bootstrap），保证封稿不至于空输入。
     */
    public List<String> collectFacts(Long eventId) {
        List<EventMention> mentions = mentionMapper.selectList(Wrappers.<EventMention>lambdaQuery()
                .eq(EventMention::getEventId, eventId)
                .orderByAsc(EventMention::getCreateTime)
                .orderByAsc(EventMention::getId));
        List<String> facts = new ArrayList<>();
        for (EventMention mention : mentions) {
            for (String fact : readStringList(mention.getFactContent())) {
                if (hasText(fact) && !facts.contains(fact)) {
                    facts.add(clip(fact, FACT_MAX_CHARS));
                }
                if (facts.size() >= FACTS_COLLECT_LIMIT) {
                    return facts;
                }
            }
        }
        return facts;
    }

    /**
     * 渲染最近提及事件注入文本（analyze 场景2指认 + 对话 Prompt 背景），无则空串。
     * EMERGING 展示「最近事实」而非 bootstrap 摘要——规则4 的增量抽取需要已记事实作对照面
     * （模型看不见旧 facts 就会复述旧信息，字面去重兜不住措辞变化）；
     * SETTLED 展示封稿归并摘要（信息含量 ≥ 原始 facts 且更短）。无 fact 的存量行回落摘要。
     */
    public String renderRecentEventRefs(Long userId) {
        List<Event> events = findRecentEvents(userId, agentProperties.getEventContextCount());
        if (events.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Event event : events) {
            boolean emerging = event.getStatus() == Event.STATUS_EMERGING;
            builder.append('#').append(event.getId()).append(' ')
                    .append(event.getName())
                    .append(emerging ? "（进行中草稿·可续说）" : "（已定稿·可再提/可引用）")
                    .append("｜上次提及:").append(REF_TIME_FORMAT.format(event.getLastMentionedAt()))
                    .append("｜共提及").append(event.getMentionCount()).append("次/重提")
                    .append(event.getRekindleCount()).append("次")
                    .append("｜情绪:").append(readCodes(event.getEmotionProfile()))
                    .append('\n');
            List<String> latest = emerging ? latestFacts(event.getId(), RECENT_FACTS_PER_EVENT) : List.of();
            if (!latest.isEmpty()) {
                builder.append("最近事实：").append(String.join("；", latest)).append('\n');
            } else {
                builder.append("摘要：").append(event.getSummary()).append('\n');
            }
        }
        return builder.toString().strip();
    }

    /**
     * 事件档案渲染（query 查询分支直检素材，模型输出 #id 经归属复验后调用）：
     * 档案头 + 摘要 + 最近事实 + 提及时间线 + 双向引用边（目标事件名带出），≤5 事件。
     * 无有效档案返回空串（调用方回落消息向量检索）。
     */
    public String renderEventDossier(Long userId, List<Long> eventIds) {
        if (userId == null || eventIds == null || eventIds.isEmpty()) {
            return "";
        }
        List<Event> events = listByIds(eventIds.stream().filter(java.util.Objects::nonNull)
                .distinct().toList()).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .limit(DOSIER_MAX_EVENTS).toList();
        if (events.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Event event : events) {
            builder.append('#').append(event.getId()).append(' ').append(event.getName())
                    .append(event.getStatus() == Event.STATUS_EMERGING ? "（进行中）" : "（已定稿）")
                    .append("｜首提:").append(DOSIER_TIME_FORMAT.format(event.getFirstMentionedAt()))
                    .append(" 最近:").append(DOSIER_TIME_FORMAT.format(event.getLastMentionedAt()))
                    .append("｜提及").append(event.getMentionCount()).append("次/重提")
                    .append(event.getRekindleCount()).append("次")
                    .append("｜情绪:").append(readCodes(event.getEmotionProfile()))
                    .append('\n')
                    .append("摘要：").append(event.getSummary()).append('\n');
            List<String> facts = latestFacts(event.getId(), DOSIER_MAX_FACTS);
            if (!facts.isEmpty()) {
                builder.append("最近事实：").append(String.join("；", facts)).append('\n');
            }
            for (EventMention mention : recentMentions(event.getId(), DOSIER_MAX_TIMELINE)) {
                builder.append(" · ").append(DOSIER_TIME_FORMAT.format(mention.getCreateTime()))
                        .append(' ').append(mentionTypeName(mention.getMentionType()))
                        .append("｜情绪:").append(readCodes(mention.getEmotionCodes()))
                        .append('\n');
            }
            for (EventRelation relation : findRelations(userId, event.getId(), DOSIER_MAX_RELATIONS)) {
                boolean outgoing = event.getId().equals(relation.getEventId());
                Long otherId = outgoing ? relation.getRefEventId() : relation.getEventId();
                Event other = load(otherId);
                builder.append(" · ")
                        .append(outgoing ? "→ 引用 " : "← 被引用 ")
                        .append('#').append(otherId)
                        .append(other != null ? " " + other.getName() : "")
                        .append('(').append(relation.getRefType());
                if (hasText(relation.getRefText())) {
                    builder.append('：').append(relation.getRefText());
                }
                builder.append(")\n");
            }
        }
        return builder.toString().strip();
    }

    /** 某事件最近提及行（时间倒序，档案时间线用） */
    private List<EventMention> recentMentions(Long eventId, int limit) {
        return mentionMapper.selectList(Wrappers.<EventMention>lambdaQuery()
                .eq(EventMention::getEventId, eventId)
                .orderByDesc(EventMention::getCreateTime)
                .last("LIMIT " + limit));
    }

    /** 某事件的双向引用边（出边+入边，档案关系网用） */
    private List<EventRelation> findRelations(Long userId, Long eventId, int limit) {
        return relationMapper.selectList(Wrappers.<EventRelation>lambdaQuery()
                .eq(EventRelation::getUserId, userId)
                .and(w -> w.eq(EventRelation::getEventId, eventId)
                        .or().eq(EventRelation::getRefEventId, eventId))
                .orderByDesc(EventRelation::getCreateTime)
                .last("LIMIT " + limit));
    }

    private String mentionTypeName(Integer mentionType) {
        if (mentionType == null) {
            return "提及";
        }
        return switch (mentionType) {
            case EventMention.TYPE_NEW -> "建档";
            case EventMention.TYPE_CONTINUATION -> "续说";
            case EventMention.TYPE_REKINDLED -> "重提";
            default -> "提及";
        };
    }

    /** 事件最近几条增量事实（时间正序，供注入展示；倒序取满即止） */
    private List<String> latestFacts(Long eventId, int limit) {
        List<EventMention> rows = mentionMapper.selectList(Wrappers.<EventMention>lambdaQuery()
                .eq(EventMention::getEventId, eventId)
                .orderByDesc(EventMention::getCreateTime)
                .orderByDesc(EventMention::getId)
                .last("LIMIT " + limit));
        List<String> facts = new ArrayList<>();
        for (EventMention row : rows) {
            facts.addAll(0, readFactsOf(row));
            if (facts.size() >= limit) {
                break;
            }
        }
        return facts.size() > limit ? facts.subList(facts.size() - limit, facts.size()) : facts;
    }

    /** 最近 N 个不同事件（按提及去重取新，供 rerank 同会话 boost 与上下文注入） */
    public List<Event> findRecentEvents(Long userId, int limit) {
        List<EventMention> mentions = mentionMapper.selectList(Wrappers.<EventMention>lambdaQuery()
                .eq(EventMention::getUserId, userId)
                .orderByDesc(EventMention::getCreateTime)
                .last("LIMIT 40"));
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (EventMention mention : mentions) {
            distinctIds.add(mention.getEventId());
            if (distinctIds.size() >= limit) {
                break;
            }
        }
        return listByIds(new ArrayList<>(distinctIds));
    }

    /** 该用户当前 EMERGING 事件（按活跃时间升序，供 open 数量上限强制封稿取最旧） */
    public List<Event> findOpenEmergingOldestFirst(Long userId) {
        return eventMapper.selectList(Wrappers.<Event>lambdaQuery()
                .eq(Event::getUserId, userId)
                .eq(Event::getStatus, Event.STATUS_EMERGING)
                .orderByAsc(Event::getLastActiveTime));
    }

    // ==================== 引用关系（事件间，非同一事件） ====================

    /**
     * 落库本事件对其他旧事件的引用边。refEventId 必须归属该用户、非自引用、类型合法，
     * 否则跳过（防模型幻觉 ID / 越权 / 把同一事件误当引用）。(event_id, ref_event_id, ref_type) 唯一。
     */
    public void saveRelations(Long userId, Long eventId, Long evidenceMessageId,
                              List<EventDraft.ReferencedEvent> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (EventDraft.ReferencedEvent ref : references) {
            if (ref == null || ref.getRefEventId() == null || ref.getRefEventId().equals(eventId)
                    || !isValidRefType(ref.getRefType())) {
                continue;
            }
            if (findOwned(ref.getRefEventId(), userId) == null) {
                log.debug("引用目标不存在或越权，跳过: eventId={}, refEventId={}", eventId, ref.getRefEventId());
                continue;
            }
            String dedupKey = ref.getRefEventId() + ":" + ref.getRefType();
            if (!seen.add(dedupKey)) {
                continue;
            }
            if (relationExists(eventId, ref.getRefEventId(), ref.getRefType())) {
                continue;
            }
            try {
                EventRelation relation = new EventRelation();
                relation.setEventId(eventId);
                relation.setRefEventId(ref.getRefEventId());
                relation.setUserId(userId);
                relation.setRefType(ref.getRefType());
                relation.setRefText(clip(ref.getRefText(), 128));
                relation.setEvidenceMessageId(evidenceMessageId);
                relation.setCreateTime(LocalDateTime.now());
                relationMapper.insert(relation);
            } catch (Exception e) {
                // 引用边是旁路增强数据，单行冲突/失败不影响事件主链路
                log.warn("事件引用边写入失败（跳过）: eventId={}, refEventId={}, type={}",
                        eventId, ref.getRefEventId(), ref.getRefType(), e);
            }
        }
    }

    /** 近期存疑归入（ambiguous=1）的提及行（用户纠正信号触发解绑复核用），按时间倒序 */
    public List<EventMention> findRecentAmbiguousMentions(Long userId, LocalDateTime since, int limit) {
        return mentionMapper.selectList(Wrappers.<EventMention>lambdaQuery()
                .eq(EventMention::getUserId, userId)
                .eq(EventMention::getAmbiguous, EventMention.AMBIGUOUS_YES)
                .ge(EventMention::getCreateTime, since)
                .orderByDesc(EventMention::getCreateTime)
                .last("LIMIT " + limit));
    }

    /** 拆分/合并通道：把给定提及行改挂到新事件，并重算源事件计数 */
    @Transactional(rollbackFor = Exception.class)
    public void reparentMentions(List<Long> mentionIds, Long targetEventId) {
        if (mentionIds == null || mentionIds.isEmpty()) {
            return;
        }
        EventMention patch = new EventMention();
        patch.setEventId(targetEventId);
        mentionMapper.update(patch, Wrappers.<EventMention>lambdaUpdate()
                .in(EventMention::getId, mentionIds));
    }

    /** 依据现存提及重算事件计数与首末提及时间（拆分后源事件收敛用） */
    @Transactional(rollbackFor = Exception.class)
    public void recountEvent(Long eventId) {
        Event event = eventMapper.selectById(eventId);
        if (event == null) {
            return;
        }
        List<EventMention> mentions = mentionMapper.selectList(Wrappers.<EventMention>lambdaQuery()
                .eq(EventMention::getEventId, eventId)
                .orderByAsc(EventMention::getCreateTime));
        event.setMentionCount(mentions.size());
        event.setRekindleCount((int) mentions.stream()
                .filter(m -> m.getMentionType() != null && m.getMentionType() == EventMention.TYPE_REKINDLED).count());
        if (!mentions.isEmpty()) {
            event.setFirstMentionedAt(mentions.get(0).getCreateTime());
            event.setLastMentionedAt(mentions.get(mentions.size() - 1).getCreateTime());
        }
        eventMapper.updateById(event);
    }

    private boolean mentionExists(Long eventId, Long messageId) {
        return mentionMapper.selectCount(Wrappers.<EventMention>lambdaQuery()
                .eq(EventMention::getEventId, eventId)
                .eq(EventMention::getMessageId, messageId)) > 0;
    }

    private boolean relationExists(Long eventId, Long refEventId, String refType) {
        return relationMapper.selectCount(Wrappers.<EventRelation>lambdaQuery()
                .eq(EventRelation::getEventId, eventId)
                .eq(EventRelation::getRefEventId, refEventId)
                .eq(EventRelation::getRefType, refType)) > 0;
    }

    private boolean isValidRefType(String refType) {
        return EventRelation.TYPE_COMPARISON.equals(refType)
                || EventRelation.TYPE_CAUSAL.equals(refType)
                || EventRelation.TYPE_MENTION.equals(refType);
    }

    private void insertMention(Long eventId, Long userId, Long conversationId, Long messageId,
                               int mentionType, EventDraft draft, int ambiguous) {
        EventMention mention = new EventMention();
        mention.setEventId(eventId);
        mention.setUserId(userId);
        mention.setMessageId(messageId);
        mention.setConversationId(conversationId);
        mention.setMentionType(mentionType);
        mention.setEmotionCodes(writeJson(draft.getEmotionCodes()));
        mention.setPsychologyCodes(writeJson(draft.getPsychologyCodes()));
        mention.setFactContent(writeJson(draft.getFacts()));
        mention.setAmbiguous(ambiguous);
        mention.setConfidence(draft.getConfidence());
        mention.setCreateTime(LocalDateTime.now());
        mentionMapper.insert(mention);
    }

    /** 建档 bootstrap 摘要：名称 + 初始事实拼接，定稿时被事实归并摘要替换 */
    private String bootstrapSummary(EventDraft draft) {
        String facts = String.join("；", draft.getFacts().stream().filter(this::hasText).toList());
        String text = hasText(facts) ? facts : safe(draft.getName());
        return clip(text, SUMMARY_MAX_CHARS);
    }

    private String readCodes(String json) {
        List<String> codes = readStringList(json);
        return codes.isEmpty() ? "（无）" : String.join("、", codes);
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.warn("事件事实/情绪码JSON解析失败: {}", e.getMessage());
            return List.of();
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("事件JSON序列化失败: {}", e.getMessage());
            return null;
        }
    }

    private boolean hasText(String text) {
        return text != null && !text.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String clip(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
