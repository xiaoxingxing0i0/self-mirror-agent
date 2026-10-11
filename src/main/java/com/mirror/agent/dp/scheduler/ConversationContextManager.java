package com.mirror.agent.dp.scheduler;

import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.dp.entity.ConversationContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 会话上下文管理（Redis，24h 过期，docs/agent_implementation.md §3.1）。
 * 支持快照机制：链路执行前留存上一稳定状态，异常时可回滚。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationContextManager {

    private static final String KEY_PREFIX = "mirror:ctx:";
    private static final String SNAPSHOT_PREFIX = "mirror:ctx:snap:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final AgentProperties agentProperties;

    /** 加载上下文，不存在则初始化空上下文 */
    public ConversationContext load(Long conversationId, Long userId) {
        Object cached = redisTemplate.opsForValue().get(KEY_PREFIX + conversationId);
        if (cached instanceof ConversationContext context) {
            return context;
        }
        ConversationContext context = new ConversationContext();
        context.setConversationId(conversationId);
        context.setUserId(userId);
        context.setLastActiveTime(LocalDateTime.now());
        return context;
    }

    /** 截取最近 N 轮后写回 Redis 并刷新 TTL */
    public void save(ConversationContext context) {
        context.trim(agentProperties.getContextMaxRounds());
        redisTemplate.opsForValue().set(KEY_PREFIX + context.getConversationId(), context,
                Duration.ofHours(agentProperties.getSessionTtlHours()));
    }

    /** 执行前留存快照（上一稳定状态） */
    public void snapshot(ConversationContext context) {
        redisTemplate.opsForValue().set(SNAPSHOT_PREFIX + context.getConversationId(), context,
                Duration.ofHours(agentProperties.getSessionTtlHours()));
    }

    /** 异常时回滚到快照 */
    public ConversationContext restoreSnapshot(Long conversationId, Long userId) {
        Object snapshot = redisTemplate.opsForValue().get(SNAPSHOT_PREFIX + conversationId);
        if (snapshot instanceof ConversationContext context) {
            log.info("会话上下文已回滚到快照: conversationId={}", conversationId);
            return context;
        }
        return load(conversationId, userId);
    }

    public void evict(Long conversationId) {
        redisTemplate.delete(KEY_PREFIX + conversationId);
        redisTemplate.delete(SNAPSHOT_PREFIX + conversationId);
    }
}
