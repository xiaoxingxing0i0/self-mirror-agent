package com.mirror.agent.dp.scheduler;

import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.Event;
import com.mirror.agent.service.EventService;
import com.mirror.agent.service.EventVectorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 事件草稿定稿轮询器：30s 扫描定稿队列，认领成功后将 EMERGING → SETTLED
 * 并做一次最终的摘要向量刷新（保证向量库中是定稿版现状摘要）。
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
                Event event = eventService.markSettled(eventId);
                if (event != null) {
                    vectorService.upsertQuietly(event);
                    log.debug("事件已定稿: eventId={}, name={}", eventId, event.getName());
                }
            } catch (Exception e) {
                log.error("事件定稿失败: eventId={}", eventId, e);
            }
        }
    }
}
