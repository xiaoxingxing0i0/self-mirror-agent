package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.IncidentAgent;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Incident;
import com.mirror.agent.entity.IncidentMention;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.AgentType;
import com.mirror.agent.message.IncidentJudgeResult;
import com.mirror.agent.service.IncidentService;
import com.mirror.agent.service.IncidentVectorService;
import com.mirror.agent.service.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 事由变更通道（事由链路 v2 的拆分半边，merge-bias 的安全网）：
 * 封稿时对 ambiguous=1 的存疑提及逐条复核——以该事由确定事实（ambiguous=0）为对照面，
 * 复用同事由裁决判定「这条事实是否属于本事由」；判定不属于则拆出为独立 EMERGING 草稿
 * （提及行改挂、标签归因随迁、引用边随迁、双侧计数重算、双方定稿刷新）。
 *
 * <p>拆错的代价低于留错：独立草稿可被既有查重通道再次归并，而混入的无关事实会持续污染摘要。
 * 复核调用失败时保留归因（不因基础设施抖动拆分）。同一包内的 merge-bias（拿不准先归入）
 * 依赖本通道成立，两者不可拆开上线。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentMutationService {

    /** 确定事实对照面渲染上限（字符），防超长 prompt */
    private static final int PLAIN_FACTS_MAX_CHARS = 600;

    private final AgentProperties agentProperties;
    private final IncidentService incidentService;
    private final TagService tagService;
    private final IncidentAgent incidentAgent;
    private final AgentInvoker invoker;
    private final IncidentFinalizeQueue finalizeQueue;
    private final IncidentVectorService vectorService;

    /**
     * 封稿复核：逐条判定存疑归入，不属于则拆出独立草稿。
     *
     * @return 是否发生了拆分（调用方需重新加载事由再走定稿）
     */
    public boolean reviewAmbiguousAttachments(Incident incident) {
        List<IncidentMention> ambiguousRows = incidentService.findAmbiguousMentions(incident.getId());
        if (ambiguousRows.isEmpty()) {
            return false;
        }
        String solidFacts = renderSolidFacts(incident.getId());
        if (solidFacts.isBlank()) {
            // 全部归入都存疑：无对照面不拆，保留在草稿中随摘要沉淀，后续叙述可自然澄清
            log.debug("事由全部提及存疑，跳过复核: incidentId={}", incident.getId());
            return false;
        }
        List<IncidentMention> toSplit = new ArrayList<>();
        for (IncidentMention row : ambiguousRows) {
            String factText = String.join("；", incidentService.readFactsOf(row));
            if (factText.isBlank()) {
                continue;
            }
            if (!judgeBelongsToIncident(incident, solidFacts, factText)) {
                toSplit.add(row);
            }
        }
        if (toSplit.isEmpty()) {
            return false;
        }
        splitToDraft(incident, toSplit);
        return true;
    }

    /** 单条存疑事实是否属于本事由（复用同事由裁决；调用失败保留归因=true） */
    private boolean judgeBelongsToIncident(Incident incident, String solidFacts, String factText) {
        try {
            String existingDesc = "名称：" + incident.getName()
                    + "；已确定事实：" + solidFacts
                    + "；核心要素：" + (incident.getCoreFacts() == null ? "（无）" : incident.getCoreFacts());
            String candidateDesc = "名称：" + incident.getName()
                    + "；待复核事实：" + factText;
            AgentResult<IncidentJudgeResult> result = invoker.invoke(AgentType.INCIDENT,
                    "review_ambiguous_fact",
                    () -> incidentAgent.judgeSameIncident(existingDesc, candidateDesc));
            return result.getData() != null && result.getData().isSame();
        } catch (Exception e) {
            log.warn("存疑归因复核失败，保留归因: incidentId={}, err={}", incident.getId(), e.getMessage());
            return true;
        }
    }

    /** 拆分执行：存疑提及改挂新 EMERGING 草稿，标签归因/引用边随迁，双侧计数与定稿刷新 */
    private void splitToDraft(Incident source, List<IncidentMention> rows) {
        List<Long> mentionIds = rows.stream().map(IncidentMention::getId).toList();
        List<Long> messageIds = rows.stream().map(IncidentMention::getMessageId)
                .filter(Objects::nonNull).distinct().toList();
        Incident draft = incidentService.createSplitDraft(source, rows);
        incidentService.reparentMentions(mentionIds, draft.getId());
        incidentService.recountIncident(source.getId());
        incidentService.reparentRelations(source.getId(), draft.getId(), messageIds);
        tagService.reattributeTagsToIncident(source.getUserId(), source.getId(),
                draft.getId(), messageIds);
        Map<Long, List<String>> messageFacts = new LinkedHashMap<>();
        for (IncidentMention row : rows) {
            if (row.getMessageId() != null) {
                messageFacts.put(row.getMessageId(), incidentService.readFactsOf(row));
            }
        }
        vectorService.moveFacts(source.getId(), draft.getId(), source.getUserId(), messageFacts);
        vectorService.upsertAsync(draft);
        finalizeQueue.touch(draft.getId(), agentProperties.getIncidentFinalizeMinutes());
        log.info("存疑归因已拆出独立草稿: sourceIncidentId={}, splitDraftId={}, 拆出提及数={}",
                source.getId(), draft.getId(), mentionIds.size());
    }

    /** 确定事实（ambiguous=0）去重拼接，作为复核对照面 */
    private String renderSolidFacts(Long incidentId) {
        Set<String> distinct = new LinkedHashSet<>();
        for (IncidentMention row : incidentService.findSolidMentions(incidentId)) {
            distinct.addAll(incidentService.readFactsOf(row).stream()
                    .filter(f -> f != null && !f.isBlank()).toList());
        }
        String text = String.join("；", distinct);
        return text.length() > PLAIN_FACTS_MAX_CHARS
                ? text.substring(0, PLAIN_FACTS_MAX_CHARS) : text;
    }
}
