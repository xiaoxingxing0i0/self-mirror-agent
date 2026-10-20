package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.EventAgent;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Event;
import com.mirror.agent.entity.EventMention;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.AgentType;
import com.mirror.agent.message.EventJudgeResult;
import com.mirror.agent.service.EventService;
import com.mirror.agent.service.EventVectorService;
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
 * 事件变更通道（事件链路 v2 的拆分半边，merge-bias 的安全网）：
 * 封稿时对 ambiguous=1 的存疑提及逐条复核——以该事件确定事实（ambiguous=0）为对照面，
 * 复用同事件裁决判定「这条事实是否属于本事件」；判定不属于则拆出为独立 EMERGING 草稿
 * （提及行改挂、标签归因随迁、引用边随迁、双侧计数重算、双方定稿刷新）。
 *
 * <p>拆错的代价低于留错：独立草稿可被既有查重通道再次归并，而混入的无关事实会持续污染摘要。
 * 复核调用失败时保留归因（不因基础设施抖动拆分）。同一包内的 merge-bias（拿不准先归入）
 * 依赖本通道成立，两者不可拆开上线。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EventMutationService {

    /** 确定事实对照面渲染上限（字符），防超长 prompt */
    private static final int PLAIN_FACTS_MAX_CHARS = 600;

    private final AgentProperties agentProperties;
    private final EventService eventService;
    private final TagService tagService;
    private final EventAgent eventAgent;
    private final AgentInvoker invoker;
    private final EventFinalizeQueue finalizeQueue;
    private final EventVectorService vectorService;

    /**
     * 封稿复核：逐条判定存疑归入，不属于则拆出独立草稿。
     *
     * @return 是否发生了拆分（调用方需重新加载事件再走定稿）
     */
    public boolean reviewAmbiguousAttachments(Event event) {
        List<EventMention> ambiguousRows = eventService.findAmbiguousMentions(event.getId());
        if (ambiguousRows.isEmpty()) {
            return false;
        }
        String solidFacts = renderSolidFacts(event.getId());
        if (solidFacts.isBlank()) {
            // 全部归入都存疑：无对照面不拆，保留在草稿中随摘要沉淀，后续叙述可自然澄清
            log.debug("事件全部提及存疑，跳过复核: eventId={}", event.getId());
            return false;
        }
        List<EventMention> toSplit = new ArrayList<>();
        for (EventMention row : ambiguousRows) {
            String factText = String.join("；", eventService.readFactsOf(row));
            if (factText.isBlank()) {
                continue;
            }
            if (!judgeBelongsToEvent(event, solidFacts, factText)) {
                toSplit.add(row);
            }
        }
        if (toSplit.isEmpty()) {
            return false;
        }
        splitToDraft(event, toSplit);
        return true;
    }

    /** 单条存疑事实是否属于本事件（复用同事件裁决；调用失败保留归因=true） */
    private boolean judgeBelongsToEvent(Event event, String solidFacts, String factText) {
        try {
            String existingDesc = "名称：" + event.getName()
                    + "；已确定事实：" + solidFacts
                    + "；核心要素：" + (event.getCoreFacts() == null ? "（无）" : event.getCoreFacts());
            String candidateDesc = "名称：" + event.getName()
                    + "；待复核事实：" + factText;
            AgentResult<EventJudgeResult> result = invoker.invoke(AgentType.EVENT,
                    "review_ambiguous_fact",
                    () -> eventAgent.judgeSameEvent(existingDesc, candidateDesc));
            return result.getData() != null && result.getData().isSame();
        } catch (Exception e) {
            log.warn("存疑归因复核失败，保留归因: eventId={}, err={}", event.getId(), e.getMessage());
            return true;
        }
    }

    /** 拆分执行：存疑提及改挂新 EMERGING 草稿，标签归因/引用边随迁，双侧计数与定稿刷新 */
    private void splitToDraft(Event source, List<EventMention> rows) {
        List<Long> mentionIds = rows.stream().map(EventMention::getId).toList();
        List<Long> messageIds = rows.stream().map(EventMention::getMessageId)
                .filter(Objects::nonNull).distinct().toList();
        Event draft = eventService.createSplitDraft(source, rows);
        eventService.reparentMentions(mentionIds, draft.getId());
        eventService.recountEvent(source.getId());
        eventService.reparentRelations(source.getId(), draft.getId(), messageIds);
        tagService.reattributeTagsToEvent(source.getUserId(), source.getId(),
                draft.getId(), messageIds);
        Map<Long, List<String>> messageFacts = new LinkedHashMap<>();
        for (EventMention row : rows) {
            if (row.getMessageId() != null) {
                messageFacts.put(row.getMessageId(), eventService.readFactsOf(row));
            }
        }
        vectorService.moveFacts(source.getId(), draft.getId(), source.getUserId(), messageFacts);
        vectorService.upsertAsync(draft);
        finalizeQueue.touch(draft.getId(), agentProperties.getEventFinalizeMinutes());
        log.info("存疑归因已拆出独立草稿: sourceEventId={}, splitDraftId={}, 拆出提及数={}",
                source.getId(), draft.getId(), mentionIds.size());
    }

    /** 确定事实（ambiguous=0）去重拼接，作为复核对照面 */
    private String renderSolidFacts(Long eventId) {
        Set<String> distinct = new LinkedHashSet<>();
        for (EventMention row : eventService.findSolidMentions(eventId)) {
            distinct.addAll(eventService.readFactsOf(row).stream()
                    .filter(f -> f != null && !f.isBlank()).toList());
        }
        String text = String.join("；", distinct);
        return text.length() > PLAIN_FACTS_MAX_CHARS
                ? text.substring(0, PLAIN_FACTS_MAX_CHARS) : text;
    }
}
