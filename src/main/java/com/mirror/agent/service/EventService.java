package com.mirror.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Event;
import com.mirror.agent.entity.EventMention;
import com.mirror.agent.message.EventDraft;
import com.mirror.agent.repository.EventMapper;
import com.mirror.agent.repository.EventMentionMapper;
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

/**
 * 事件与提及的持久化服务（MySQL）+ 上下文注入渲染。
 * 事件归 user_id 维度；event_mention 为统计与溯源明细，event 表计数为写时冗余。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventService {

    private static final DateTimeFormatter REF_TIME_FORMAT =
            DateTimeFormatter.ofPattern("MM-dd HH:mm");
    /** 判定"摘要发生实质变化"的净增字数阈值（向量刷新节流） */
    private static final int SUMMARY_VECTOR_DELTA_CHARS = 10;
    /** 摘要长度上限（与 entity/Event 注释口径一致） */
    private static final int SUMMARY_MAX_CHARS = 300;

    private final EventMapper eventMapper;
    private final EventMentionMapper mentionMapper;
    private final AgentProperties agentProperties;
    private final ObjectMapper objectMapper;

    /** 查询归属该用户的事件（防越权/防模型幻觉 ID） */
    public Event findOwned(Long eventId, Long userId) {
        if (eventId == null || userId == null) {
            return null;
        }
        Event event = eventMapper.selectOne(Wrappers.<Event>lambdaQuery()
                .eq(Event::getId, eventId).eq(Event::getUserId, userId));
        return event;
    }

    public List<Event> listByIds(List<Long> eventIds) {
        return eventIds.isEmpty() ? List.of() : eventMapper.selectByIds(eventIds);
    }

    /** 新事件建档（EMERGING）+ 落 NEW 提及（溯源取本轮最后一条消息，合并轮覆盖全部碎片） */
    @Transactional(rollbackFor = Exception.class)
    public Event createEvent(Long userId, EventDraft draft, Long conversationId,
                             List<Long> turnMessageIds) {
        Long messageId = turnMessageIds.get(turnMessageIds.size() - 1);
        Event event = new Event();
        event.setUserId(userId);
        event.setName(clip(draft.getName(), 60));
        event.setSummary(clip(draft.getSummaryDraft(), SUMMARY_MAX_CHARS));
        event.setStatus(Event.STATUS_EMERGING);
        event.setCoreFacts(writeJson(draft.getCoreFacts()));
        event.setEmotionProfile(writeJson(draft.getEmotionCodes()));
        event.setMentionCount(1);
        event.setRekindleCount(0);
        event.setFirstMentionedAt(LocalDateTime.now());
        event.setLastMentionedAt(LocalDateTime.now());
        event.setLastActiveTime(LocalDateTime.now());
        eventMapper.insert(event);
        insertMention(event.getId(), userId, conversationId, messageId,
                EventMention.TYPE_NEW, draft);
        return event;
    }

    /** 既有事件被再次提及/延续：落 mention、更新计数与现状摘要（不新建行） */
    @Transactional(rollbackFor = Exception.class)
    public void applyKnownEvent(Event event, EventDraft draft, int mentionType,
                                Long conversationId, List<Long> turnMessageIds) {
        insertMention(event.getId(), event.getUserId(), conversationId,
                turnMessageIds.get(turnMessageIds.size() - 1), mentionType, draft);
        event.setMentionCount(event.getMentionCount() + 1);
        if (mentionType == EventMention.TYPE_REKINDLED) {
            event.setRekindleCount(event.getRekindleCount() + 1);
        }
        event.setLastMentionedAt(LocalDateTime.now());
        event.setLastActiveTime(LocalDateTime.now());
        if (hasText(draft.getSummaryDraft())) {
            event.setSummary(clip(draft.getSummaryDraft(), SUMMARY_MAX_CHARS));
        }
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
    }

    /** 草稿到期定稿（EMERGING → SETTLED） */
    public Event markSettled(Long eventId) {
        Event event = eventMapper.selectById(eventId);
        if (event == null || event.getStatus() != Event.STATUS_EMERGING) {
            return null;
        }
        event.setStatus(Event.STATUS_SETTLED);
        eventMapper.updateById(event);
        return event;
    }

    /** 摘要是否发生实质变化（节流刷新向量的依据） */
    public boolean summaryMateriallyChanged(String oldSummary, String newSummary) {
        if (!hasText(newSummary) || newSummary.equals(oldSummary)) {
            return false;
        }
        if (!hasText(oldSummary)) {
            return true;
        }
        return newSummary.length() - oldSummary.length() >= SUMMARY_VECTOR_DELTA_CHARS;
    }

    /** 渲染最近提及事件注入文本（analyze 场景2指认 + 对话 Prompt 背景），无则空串 */
    public String renderRecentEventRefs(Long userId) {
        List<Event> events = findRecentEvents(userId, agentProperties.getEventContextCount());
        if (events.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (Event event : events) {
            builder.append('#').append(event.getId()).append(' ')
                    .append(event.getName())
                    .append("｜上次提及:").append(REF_TIME_FORMAT.format(event.getLastMentionedAt()))
                    .append("｜共提及").append(event.getMentionCount()).append("次/重提")
                    .append(event.getRekindleCount()).append("次")
                    .append("｜情绪:").append(readCodes(event.getEmotionProfile()))
                    .append("\n摘要：").append(event.getSummary()).append('\n');
        }
        return builder.toString().strip();
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

    private void insertMention(Long eventId, Long userId, Long conversationId, Long messageId,
                               int mentionType, EventDraft draft) {
        EventMention mention = new EventMention();
        mention.setEventId(eventId);
        mention.setUserId(userId);
        mention.setMessageId(messageId);
        mention.setConversationId(conversationId);
        mention.setMentionType(mentionType);
        mention.setEmotionCodes(writeJson(draft.getEmotionCodes()));
        mention.setPsychologyCodes(writeJson(draft.getPsychologyCodes()));
        mention.setConfidence(draft.getConfidence());
        mention.setCreateTime(LocalDateTime.now());
        mentionMapper.insert(mention);
    }

    private String readCodes(String json) {
        List<String> codes = json == null || json.isBlank()
                ? List.of() : readList(json);
        return codes.isEmpty() ? "（无）" : String.join("、", codes);
    }

    private List<String> readList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            log.warn("事件情绪码解析失败: {}", e.getMessage());
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

    private String clip(String text, int maxLen) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen);
    }
}
