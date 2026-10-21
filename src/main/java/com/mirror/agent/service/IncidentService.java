package com.mirror.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Incident;
import com.mirror.agent.entity.IncidentMention;
import com.mirror.agent.entity.IncidentRelation;
import com.mirror.agent.message.IncidentDraft;
import com.mirror.agent.repository.IncidentMapper;
import com.mirror.agent.repository.IncidentMentionMapper;
import com.mirror.agent.repository.IncidentRelationMapper;
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
 * 事由 / 提及 / 引用关系的持久化服务（MySQL）+ 上下文注入渲染。
 *
 * <p>事由链路 v2「事实稳定层」：incident_mention.fact_content 为只追加的增量事实层，
 * 事由现状摘要（incident.summary）在建档时写 bootstrap（名称+初始事实），
 * 封稿时由 {@link #collectFacts} 归并全部事实一次性生成，中途不逐轮改写（防摘要漂移）。
 * incident_relation 只存不同事由间的引用（对比/因果/提及）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IncidentService {

    private static final DateTimeFormatter REF_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 摘要长度上限（与 entity/Incident 注释口径一致） */
    private static final int SUMMARY_MAX_CHARS = 300;
    /** 单条增量事实长度上限（与提示词「≤50字」口径一致，冗余放宽到 120 容错） */
    private static final int FACT_MAX_CHARS = 120;
    /** 单事由单次归并的事实条数上限（封稿摘要生成截断，防超长 prompt） */
    private static final int FACTS_COLLECT_LIMIT = 40;
    /** 上下文注入中 EMERGING 事由展示的最近事实条数（增量抽取的对照面） */
    private static final int RECENT_FACTS_PER_EVENT = 3;
    /** 事由档案渲染上限（query 直检：事由数/事实数/时间线/引用边） */
    private static final DateTimeFormatter DOSIER_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int DOSIER_MAX_EVENTS = 5;
    private static final int DOSIER_MAX_FACTS = 5;
    private static final int DOSIER_MAX_TIMELINE = 8;
    private static final int DOSIER_MAX_RELATIONS = 10;

    private final IncidentMapper incidentMapper;
    private final IncidentMentionMapper mentionMapper;
    private final IncidentRelationMapper relationMapper;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    /** 按 ID 加载事由（定稿器/拆分通道内部使用，归属校验在调用方入口完成） */
    public Incident load(Long incidentId) {
        return incidentId == null ? null : incidentMapper.selectById(incidentId);
    }

    /**
     * 已定稿事由新增提及后的摘要刷新（派生缓存重建，状态保持 SETTLED）。
     *
     * @param sceneCategory 场景分类（19 类白名单标签）；null/空白 = 本轮未判定，保留既有值不覆盖
     */
    public void refreshSummary(Long incidentId, String mergedSummary, String sceneCategory) {
        Incident incident = incidentMapper.selectById(incidentId);
        if (incident == null || !hasText(mergedSummary)) {
            return;
        }
        incident.setSummary(clip(mergedSummary, SUMMARY_MAX_CHARS));
        if (hasText(sceneCategory)) {
            incident.setSceneCategory(sceneCategory.trim());
        }
        incidentMapper.updateById(incident);
    }

    /** 某事由的存疑归入（ambiguous=1）提及行（封稿复核拆分用） */
    public List<IncidentMention> findAmbiguousMentions(Long incidentId) {
        return mentionMapper.selectList(Wrappers.<IncidentMention>lambdaQuery()
                .eq(IncidentMention::getIncidentId, incidentId)
                .eq(IncidentMention::getAmbiguous, IncidentMention.AMBIGUOUS_YES)
                .orderByAsc(IncidentMention::getCreateTime));
    }

    /** 某事由的确定提及行（ambiguous=0 或历史空值），存疑复核的对照面 */
    public List<IncidentMention> findSolidMentions(Long incidentId) {
        return mentionMapper.selectList(Wrappers.<IncidentMention>lambdaQuery()
                .eq(IncidentMention::getIncidentId, incidentId)
                .and(w -> w.eq(IncidentMention::getAmbiguous, IncidentMention.AMBIGUOUS_NO)
                        .or().isNull(IncidentMention::getAmbiguous))
                .orderByAsc(IncidentMention::getCreateTime));
    }

    /** 解析提及行的增量事实 JSON 数组（拆分/摘要素材用） */
    public List<String> readFactsOf(IncidentMention mention) {
        return readStringList(mention.getFactContent());
    }

    /**
     * 拆分建档：把误归入的存疑提及拆出为独立 EMERGING 草稿。
     * 名称取拆出首条事实截断，摘要=拆出事实拼接，核心要素沿用源事由（拆分后由后续叙述继续修正）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Incident createSplitDraft(Incident source, List<IncidentMention> splitRows) {
        LocalDateTime now = LocalDateTime.now();
        List<String> facts = new ArrayList<>();
        for (IncidentMention row : splitRows) {
            facts.addAll(readFactsOf(row));
        }
        Incident incident = new Incident();
        incident.setUserId(source.getUserId());
        String firstName = facts.isEmpty()
                ? source.getName() + "(拆出待确认)" : clip(facts.get(0), 60);
        incident.setName(clip(firstName, 60));
        incident.setSummary(clip(facts.isEmpty() ? firstName : String.join("；", facts), SUMMARY_MAX_CHARS));
        incident.setStatus(Incident.STATUS_EMERGING);
        incident.setCoreFacts(source.getCoreFacts());
        incident.setEmotionProfile(splitRows.get(0).getEmotionCodes());
        incident.setMentionCount(splitRows.size());
        incident.setRekindleCount(0);
        incident.setFirstMentionedAt(splitRows.get(0).getCreateTime());
        incident.setLastMentionedAt(splitRows.get(splitRows.size() - 1).getCreateTime());
        incident.setLastActiveTime(now);
        incidentMapper.insert(incident);
        return incident;
    }

    /** 拆分连带迁移引用边：源事由中以拆出消息为证据的出边挂到新事由 */
    public void reparentRelations(Long fromIncidentId, Long toIncidentId, List<Long> evidenceMessageIds) {
        if (evidenceMessageIds == null || evidenceMessageIds.isEmpty()) {
            return;
        }
        IncidentRelation patch = new IncidentRelation();
        patch.setIncidentId(toIncidentId);
        relationMapper.update(patch, Wrappers.<IncidentRelation>lambdaUpdate()
                .eq(IncidentRelation::getIncidentId, fromIncidentId)
                .in(IncidentRelation::getEvidenceMessageId, evidenceMessageIds));
    }

    /** 查询归属该用户的事由（防越权/防模型幻觉 ID） */
    public Incident findOwned(Long incidentId, Long userId) {
        if (incidentId == null || userId == null) {
            return null;
        }
        return incidentMapper.selectOne(Wrappers.<Incident>lambdaQuery()
                .eq(Incident::getId, incidentId).eq(Incident::getUserId, userId));
    }

    public List<Incident> listByIds(List<Long> incidentIds) {
        return incidentIds.isEmpty() ? List.of() : incidentMapper.selectByIds(incidentIds);
    }

    /**
     * 新事由建档（EMERGING）+ 落 NEW 提及（溯源取本轮最后一条消息，合并轮覆盖全部碎片）。
     * summary 写 bootstrap（名称+初始事实拼接），定稿时被事实归并摘要覆盖。
     */
    @Transactional(rollbackFor = Exception.class)
    public Incident createIncident(Long userId, IncidentDraft draft, Long conversationId,
                             List<Long> turnMessageIds) {
        Long messageId = turnMessageIds.get(turnMessageIds.size() - 1);
        LocalDateTime now = LocalDateTime.now();
        Incident incident = new Incident();
        incident.setUserId(userId);
        incident.setName(clip(draft.getName(), 60));
        incident.setSummary(bootstrapSummary(draft));
        incident.setStatus(Incident.STATUS_EMERGING);
        incident.setCoreFacts(writeJson(draft.getCoreFacts()));
        incident.setEmotionProfile(writeJson(draft.getEmotionCodes()));
        incident.setMentionCount(1);
        incident.setRekindleCount(0);
        incident.setFirstMentionedAt(now);
        incident.setLastMentionedAt(now);
        incident.setLastActiveTime(now);
        incidentMapper.insert(incident);
        insertMention(incident.getId(), userId, conversationId, messageId,
                IncidentMention.TYPE_NEW, draft, IncidentMention.AMBIGUOUS_NO);
        return incident;
    }

    /**
     * 既有事由被延续/重提/存疑归入：只追加事实层 mention（不改写 summary），
     * 刷新计数、活跃时间、核心要素与情绪冗余。ambiguous=YES 承载 merge-bias 存疑归入。
     * 幂等：同事由同 message_id 已存在提及则跳过。
     *
     * @return true=本次真实追加（调用方据此写事实向量）；false=重复消费被幂等跳过
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean appendMention(Incident incident, IncidentDraft draft, int mentionType, int ambiguous,
                                 Long conversationId, List<Long> turnMessageIds) {
        Long messageId = turnMessageIds.get(turnMessageIds.size() - 1);
        if (mentionExists(incident.getId(), messageId)) {
            log.debug("提及已存在，幂等跳过: incidentId={}, messageId={}", incident.getId(), messageId);
            return false;
        }
        insertMention(incident.getId(), incident.getUserId(), conversationId, messageId,
                mentionType, draft, ambiguous);
        incident.setMentionCount(incident.getMentionCount() + 1);
        if (mentionType == IncidentMention.TYPE_REKINDLED) {
            incident.setRekindleCount(incident.getRekindleCount() + 1);
        }
        LocalDateTime now = LocalDateTime.now();
        incident.setLastMentionedAt(now);
        incident.setLastActiveTime(now);
        if (hasText(draft.getName())) {
            incident.setName(clip(draft.getName(), 60));
        }
        if (draft.getCoreFacts() != null && !draft.getCoreFacts().isEmpty()) {
            incident.setCoreFacts(writeJson(draft.getCoreFacts()));
        }
        if (draft.getEmotionCodes() != null && !draft.getEmotionCodes().isEmpty()) {
            incident.setEmotionProfile(writeJson(draft.getEmotionCodes()));
        }
        incidentMapper.updateById(incident);
        return true;
    }

    /**
     * 草稿到期定稿（EMERGING → SETTLED），summary 替换为事实归并生成的现状摘要。
     *
     * @param sceneCategory 场景分类（19 类白名单标签）；null/空白 = LLM 降级未判定，保持 NULL/原值
     */
    @Transactional(rollbackFor = Exception.class)
    public Incident finalizeIncident(Long incidentId, String mergedSummary, String sceneCategory) {
        Incident incident = incidentMapper.selectById(incidentId);
        if (incident == null || incident.getStatus() != Incident.STATUS_EMERGING) {
            return null;
        }
        incident.setStatus(Incident.STATUS_SETTLED);
        if (hasText(mergedSummary)) {
            incident.setSummary(clip(mergedSummary, SUMMARY_MAX_CHARS));
        }
        if (hasText(sceneCategory)) {
            incident.setSceneCategory(sceneCategory.trim());
        }
        incidentMapper.updateById(incident);
        return incident;
    }

    /**
     * 收集事由全部增量事实（按提及时间升序，幂等去重后），供封稿摘要生成。
     * 无事实时回退 summary（bootstrap），保证封稿不至于空输入。
     */
    public List<String> collectFacts(Long incidentId) {
        List<IncidentMention> mentions = mentionMapper.selectList(Wrappers.<IncidentMention>lambdaQuery()
                .eq(IncidentMention::getIncidentId, incidentId)
                .orderByAsc(IncidentMention::getCreateTime)
                .orderByAsc(IncidentMention::getId));
        List<String> facts = new ArrayList<>();
        for (IncidentMention mention : mentions) {
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
     * 渲染最近提及事由注入文本（analyze 场景2指认 + 对话 Prompt 背景），无则空串。
     * EMERGING 展示「最近事实」而非 bootstrap 摘要——规则4 的增量抽取需要已记事实作对照面
     * （模型看不见旧 facts 就会复述旧信息，字面去重兜不住措辞变化）；
     * SETTLED 展示封稿归并摘要（信息含量 ≥ 原始 facts 且更短）。无 fact 的存量行回落摘要。
     */
    public String renderRecentIncidentRefs(Long userId) {
        List<Incident> incidents = findRecentIncidents(userId, agentProperties.getIncidentContextCount());
        if (incidents.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Incident incident : incidents) {
            boolean emerging = incident.getStatus() == Incident.STATUS_EMERGING;
            builder.append('#').append(incident.getId()).append(' ')
                    .append(incident.getName())
                    .append(emerging ? "（进行中草稿·可续说）" : "（已定稿·可再提/可引用）")
                    .append("｜上次提及:").append(REF_TIME_FORMAT.format(incident.getLastMentionedAt()))
                    .append("｜共提及").append(incident.getMentionCount()).append("次/重提")
                    .append(incident.getRekindleCount()).append("次")
                    .append("｜情绪:").append(readCodes(incident.getEmotionProfile()))
                    .append('\n');
            List<String> latest = emerging ? latestFacts(incident.getId(), RECENT_FACTS_PER_EVENT) : List.of();
            if (!latest.isEmpty()) {
                builder.append("最近事实：").append(String.join("；", latest)).append('\n');
            } else {
                builder.append("摘要：").append(incident.getSummary()).append('\n');
            }
        }
        return builder.toString().strip();
    }

    /**
     * 事由档案渲染（query 查询分支直检素材，模型输出 #id 经归属复验后调用）：
     * 档案头 + 摘要 + 最近事实 + 提及时间线 + 双向引用边（目标事由名带出），≤5 事由。
     * 无有效档案返回空串（调用方回落消息向量检索）。
     */
    public String renderIncidentDossier(Long userId, List<Long> incidentIds) {
        if (userId == null || incidentIds == null || incidentIds.isEmpty()) {
            return "";
        }
        List<Incident> incidents = listByIds(incidentIds.stream().filter(java.util.Objects::nonNull)
                .distinct().toList()).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .limit(DOSIER_MAX_EVENTS).toList();
        if (incidents.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Incident incident : incidents) {
            builder.append('#').append(incident.getId()).append(' ').append(incident.getName())
                    .append(incident.getStatus() == Incident.STATUS_EMERGING ? "（进行中）" : "（已定稿）")
                    .append("｜首提:").append(DOSIER_TIME_FORMAT.format(incident.getFirstMentionedAt()))
                    .append(" 最近:").append(DOSIER_TIME_FORMAT.format(incident.getLastMentionedAt()))
                    .append("｜提及").append(incident.getMentionCount()).append("次/重提")
                    .append(incident.getRekindleCount()).append("次")
                    .append("｜情绪:").append(readCodes(incident.getEmotionProfile()))
                    .append('\n')
                    .append("摘要：").append(incident.getSummary()).append('\n');
            List<String> facts = latestFacts(incident.getId(), DOSIER_MAX_FACTS);
            if (!facts.isEmpty()) {
                builder.append("最近事实：").append(String.join("；", facts)).append('\n');
            }
            for (IncidentMention mention : recentMentions(incident.getId(), DOSIER_MAX_TIMELINE)) {
                builder.append(" · ").append(DOSIER_TIME_FORMAT.format(mention.getCreateTime()))
                        .append(' ').append(mentionTypeName(mention.getMentionType()))
                        .append("｜情绪:").append(readCodes(mention.getEmotionCodes()))
                        .append('\n');
            }
            for (IncidentRelation relation : findRelations(userId, incident.getId(), DOSIER_MAX_RELATIONS)) {
                boolean outgoing = incident.getId().equals(relation.getIncidentId());
                Long otherId = outgoing ? relation.getRefIncidentId() : relation.getIncidentId();
                Incident other = load(otherId);
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

    /** 某事由最近提及行（时间倒序，档案时间线用） */
    private List<IncidentMention> recentMentions(Long incidentId, int limit) {
        return mentionMapper.selectList(Wrappers.<IncidentMention>lambdaQuery()
                .eq(IncidentMention::getIncidentId, incidentId)
                .orderByDesc(IncidentMention::getCreateTime)
                .last("LIMIT " + limit));
    }

    /** 某事由的双向引用边（出边+入边，档案关系网用） */
    private List<IncidentRelation> findRelations(Long userId, Long incidentId, int limit) {
        return relationMapper.selectList(Wrappers.<IncidentRelation>lambdaQuery()
                .eq(IncidentRelation::getUserId, userId)
                .and(w -> w.eq(IncidentRelation::getIncidentId, incidentId)
                        .or().eq(IncidentRelation::getRefIncidentId, incidentId))
                .orderByDesc(IncidentRelation::getCreateTime)
                .last("LIMIT " + limit));
    }

    private String mentionTypeName(Integer mentionType) {
        if (mentionType == null) {
            return "提及";
        }
        return switch (mentionType) {
            case IncidentMention.TYPE_NEW -> "建档";
            case IncidentMention.TYPE_CONTINUATION -> "续说";
            case IncidentMention.TYPE_REKINDLED -> "重提";
            default -> "提及";
        };
    }

    /** 事由最近几条增量事实（时间正序，供注入展示；倒序取满即止） */
    private List<String> latestFacts(Long incidentId, int limit) {
        List<IncidentMention> rows = mentionMapper.selectList(Wrappers.<IncidentMention>lambdaQuery()
                .eq(IncidentMention::getIncidentId, incidentId)
                .orderByDesc(IncidentMention::getCreateTime)
                .orderByDesc(IncidentMention::getId)
                .last("LIMIT " + limit));
        List<String> facts = new ArrayList<>();
        for (IncidentMention row : rows) {
            facts.addAll(0, readFactsOf(row));
            if (facts.size() >= limit) {
                break;
            }
        }
        return facts.size() > limit ? facts.subList(facts.size() - limit, facts.size()) : facts;
    }

    /** 最近 N 个不同事由（按提及去重取新，供 rerank 同会话 boost 与上下文注入） */
    public List<Incident> findRecentIncidents(Long userId, int limit) {
        List<IncidentMention> mentions = mentionMapper.selectList(Wrappers.<IncidentMention>lambdaQuery()
                .eq(IncidentMention::getUserId, userId)
                .orderByDesc(IncidentMention::getCreateTime)
                .last("LIMIT 40"));
        LinkedHashSet<Long> distinctIds = new LinkedHashSet<>();
        for (IncidentMention mention : mentions) {
            distinctIds.add(mention.getIncidentId());
            if (distinctIds.size() >= limit) {
                break;
            }
        }
        return listByIds(new ArrayList<>(distinctIds));
    }

    /** 该用户当前 EMERGING 事由（按活跃时间升序，供 open 数量上限强制封稿取最旧） */
    public List<Incident> findOpenEmergingOldestFirst(Long userId) {
        return incidentMapper.selectList(Wrappers.<Incident>lambdaQuery()
                .eq(Incident::getUserId, userId)
                .eq(Incident::getStatus, Incident.STATUS_EMERGING)
                .orderByAsc(Incident::getLastActiveTime));
    }

    // ==================== 引用关系（事由间，非同一事由） ====================

    /**
     * 落库本事由对其他旧事由的引用边。refIncidentId 必须归属该用户、非自引用、类型合法，
     * 否则跳过（防模型幻觉 ID / 越权 / 把同一事由误当引用）。(incident_id, ref_event_id, ref_type) 唯一。
     */
    public void saveRelations(Long userId, Long incidentId, Long evidenceMessageId,
                              List<IncidentDraft.ReferencedIncident> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (IncidentDraft.ReferencedIncident ref : references) {
            if (ref == null || ref.getRefIncidentId() == null || ref.getRefIncidentId().equals(incidentId)
                    || !isValidRefType(ref.getRefType())) {
                continue;
            }
            if (findOwned(ref.getRefIncidentId(), userId) == null) {
                log.debug("引用目标不存在或越权，跳过: incidentId={}, refIncidentId={}", incidentId, ref.getRefIncidentId());
                continue;
            }
            String dedupKey = ref.getRefIncidentId() + ":" + ref.getRefType();
            if (!seen.add(dedupKey)) {
                continue;
            }
            if (relationExists(incidentId, ref.getRefIncidentId(), ref.getRefType())) {
                continue;
            }
            try {
                IncidentRelation relation = new IncidentRelation();
                relation.setIncidentId(incidentId);
                relation.setRefIncidentId(ref.getRefIncidentId());
                relation.setUserId(userId);
                relation.setRefType(ref.getRefType());
                relation.setRefText(clip(ref.getRefText(), 128));
                relation.setEvidenceMessageId(evidenceMessageId);
                relation.setCreateTime(LocalDateTime.now());
                relationMapper.insert(relation);
            } catch (Exception e) {
                // 引用边是旁路增强数据，单行冲突/失败不影响事由主链路
                log.warn("事由引用边写入失败（跳过）: incidentId={}, refIncidentId={}, type={}",
                        incidentId, ref.getRefIncidentId(), ref.getRefType(), e);
            }
        }
    }

    /** 近期存疑归入（ambiguous=1）的提及行（用户纠正信号触发解绑复核用），按时间倒序 */
    public List<IncidentMention> findRecentAmbiguousMentions(Long userId, LocalDateTime since, int limit) {
        return mentionMapper.selectList(Wrappers.<IncidentMention>lambdaQuery()
                .eq(IncidentMention::getUserId, userId)
                .eq(IncidentMention::getAmbiguous, IncidentMention.AMBIGUOUS_YES)
                .ge(IncidentMention::getCreateTime, since)
                .orderByDesc(IncidentMention::getCreateTime)
                .last("LIMIT " + limit));
    }

    /** 拆分/合并通道：把给定提及行改挂到新事由，并重算源事由计数 */
    @Transactional(rollbackFor = Exception.class)
    public void reparentMentions(List<Long> mentionIds, Long targetIncidentId) {
        if (mentionIds == null || mentionIds.isEmpty()) {
            return;
        }
        IncidentMention patch = new IncidentMention();
        patch.setIncidentId(targetIncidentId);
        mentionMapper.update(patch, Wrappers.<IncidentMention>lambdaUpdate()
                .in(IncidentMention::getId, mentionIds));
    }

    /** 依据现存提及重算事由计数与首末提及时间（拆分后源事由收敛用） */
    @Transactional(rollbackFor = Exception.class)
    public void recountIncident(Long incidentId) {
        Incident incident = incidentMapper.selectById(incidentId);
        if (incident == null) {
            return;
        }
        List<IncidentMention> mentions = mentionMapper.selectList(Wrappers.<IncidentMention>lambdaQuery()
                .eq(IncidentMention::getIncidentId, incidentId)
                .orderByAsc(IncidentMention::getCreateTime));
        incident.setMentionCount(mentions.size());
        incident.setRekindleCount((int) mentions.stream()
                .filter(m -> m.getMentionType() != null && m.getMentionType() == IncidentMention.TYPE_REKINDLED).count());
        if (!mentions.isEmpty()) {
            incident.setFirstMentionedAt(mentions.get(0).getCreateTime());
            incident.setLastMentionedAt(mentions.get(mentions.size() - 1).getCreateTime());
        }
        incidentMapper.updateById(incident);
    }

    private boolean mentionExists(Long incidentId, Long messageId) {
        return mentionMapper.selectCount(Wrappers.<IncidentMention>lambdaQuery()
                .eq(IncidentMention::getIncidentId, incidentId)
                .eq(IncidentMention::getMessageId, messageId)) > 0;
    }

    private boolean relationExists(Long incidentId, Long refIncidentId, String refType) {
        return relationMapper.selectCount(Wrappers.<IncidentRelation>lambdaQuery()
                .eq(IncidentRelation::getIncidentId, incidentId)
                .eq(IncidentRelation::getRefIncidentId, refIncidentId)
                .eq(IncidentRelation::getRefType, refType)) > 0;
    }

    private boolean isValidRefType(String refType) {
        return IncidentRelation.TYPE_COMPARISON.equals(refType)
                || IncidentRelation.TYPE_CAUSAL.equals(refType)
                || IncidentRelation.TYPE_MENTION.equals(refType);
    }

    private void insertMention(Long incidentId, Long userId, Long conversationId, Long messageId,
                               int mentionType, IncidentDraft draft, int ambiguous) {
        IncidentMention mention = new IncidentMention();
        mention.setIncidentId(incidentId);
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
    private String bootstrapSummary(IncidentDraft draft) {
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
            log.warn("事由事实/情绪码JSON解析失败: {}", e.getMessage());
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
            log.warn("事由JSON序列化失败: {}", e.getMessage());
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
