package com.mirror.agent.dp.scheduler;

import com.mirror.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 归集截止时间轮询器：每 2s 扫描 Redis ZSET（mirror:agg:deadline）取到期会话，
 * ZREM 原子认领成功后冲刷缓冲 —— 由 Agent 主动发起澄清追问（先落库、再 SSE 投递）。
 * 多实例部署时认领互斥，保证同一会话超时追问只生成一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AggregationDeadlinePoller {

    /** 单次扫描认领的会话上限 */
    private static final int BATCH_LIMIT = 20;

    private final AgentProperties agentProperties;
    private final MessageAggregator aggregator;
    private final AggregationCoordinator coordinator;

    @Scheduled(fixedDelay = 2000)
    public void pollDeadlines() {
        if (!agentProperties.isFragmentAggregationEnabled()) {
            return;
        }
        for (String member : aggregator.dueConversations(BATCH_LIMIT)) {
            long conversationId = Long.parseLong(member);
            if (!aggregator.claim(conversationId)) {
                continue;
            }
            try {
                coordinator.flushTimedOut(conversationId);
            } catch (Exception e) {
                log.error("归集超时冲刷失败: conversationId={}", conversationId, e);
            }
        }
    }
}
