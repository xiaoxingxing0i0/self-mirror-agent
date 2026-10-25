package com.mirror.agent.dp.scheduler;

import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.dp.entity.ConversationContext;
import com.mirror.agent.message.PendingPeriod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 会话上下文管理（Redis，持久存储不过期，docs/agent_implementation.md §3.1）。
 *
 * <p>过期语义：不设 TTL（TTL=-1 持久键），会话上下文与快照常驻 Redis；
 * 内存增长靠轮数 FIFO 截断控制，主动清理走 {@link #evict}。</p>
 *
 * <p>短期记忆定位：键为 用户ID+会话ID 双维度（mirror:ctx:{userId}:{conversationId}），
 * 不同会话分别独立存储；单会话内消息按轮数上限 FIFO 截断（trim 保留最近 N 轮，最旧先丢弃），
 * 被淘汰的更早内容由 Milvus 用户级历史检索兜底，message 表始终为事实源。</p>
 *
 * <p>支持快照机制：链路执行前留存上一稳定状态，异常时可回滚。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationContextManager {

    private static final String KEY_PREFIX = "mirror:ctx:";
    private static final String SNAPSHOT_PREFIX = "mirror:ctx:snap:";
    private static final String PENDING_PERIOD_PREFIX = "mirror:pending:period:";

    private final RedisTemplate<String, Object> redisTemplate;
    private final AgentProperties agentProperties;

    /** 加载上下文，不存在则初始化空上下文 */
    public ConversationContext load(Long conversationId, Long userId) {
        Object cached = redisTemplate.opsForValue().get(contextKey(userId, conversationId));
        if (cached instanceof ConversationContext context) {
            return context;
        }
        ConversationContext context = new ConversationContext();
        context.setConversationId(conversationId);
        context.setUserId(userId);
        context.setLastActiveTime(LocalDateTime.now());
        return context;
    }

    /** 截取最近 N 轮后写回 Redis（持久键，不设过期） */
    public void save(ConversationContext context) {
        context.trim(agentProperties.getContextMaxRounds());
        redisTemplate.opsForValue().set(contextKey(context.getUserId(), context.getConversationId()), context);
    }

    /** 执行前留存快照（上一稳定状态），持久键不设过期 */
    public void snapshot(ConversationContext context) {
        redisTemplate.opsForValue().set(snapshotKey(context.getUserId(), context.getConversationId()), context);
    }

    /** 异常时回滚到快照 */
    public ConversationContext restoreSnapshot(Long conversationId, Long userId) {
        Object snapshot = redisTemplate.opsForValue().get(snapshotKey(userId, conversationId));
        if (snapshot instanceof ConversationContext context) {
            log.info("会话上下文已回滚到快照: conversationId={}", conversationId);
            return context;
        }
        return load(conversationId, userId);
    }

    /** 主动清除某用户某会话的短期记忆（上下文 + 快照 + 周期澄清待办槽） */
    public void evict(Long userId, Long conversationId) {
        redisTemplate.delete(contextKey(userId, conversationId));
        redisTemplate.delete(snapshotKey(userId, conversationId));
        redisTemplate.delete(pendingPeriodKey(userId, conversationId));
    }

    // ==================== 报告周期澄清待办槽（TTL 短键，与会话上下文同维度） ====================

    /** 挂起周期澄清（TTL 分钟级，到点自动失效；同会话重复挂起直接覆盖，保证槽唯一） */
    public void putPendingPeriod(Long userId, Long conversationId, PendingPeriod slot) {
        redisTemplate.opsForValue().set(pendingPeriodKey(userId, conversationId), slot,
                Duration.ofMinutes(agentProperties.getReportPeriodClarifyTtlMinutes()));
    }

    /** 读取待办槽，无则 null（消费方负责删除，见 IntentRouter.pendingPeriodGuard） */
    public PendingPeriod loadPendingPeriod(Long userId, Long conversationId) {
        Object cached = redisTemplate.opsForValue().get(pendingPeriodKey(userId, conversationId));
        return cached instanceof PendingPeriod slot ? slot : null;
    }

    /** 消费/丢弃待办槽 */
    public void clearPendingPeriod(Long userId, Long conversationId) {
        redisTemplate.delete(pendingPeriodKey(userId, conversationId));
    }

    /** 待办槽键：与上下文键同维度（用户ID+会话ID），会话间隔离 */
    private String pendingPeriodKey(Long userId, Long conversationId) {
        return PENDING_PERIOD_PREFIX + userId + ":" + conversationId;
    }

    /** 上下文键：用户ID + 会话ID 双维度，会话间隔离存储 */
    private String contextKey(Long userId, Long conversationId) {
        return KEY_PREFIX + userId + ":" + conversationId;
    }

    /** 快照键：与上下文键同维度 */
    private String snapshotKey(Long userId, Long conversationId) {
        return SNAPSHOT_PREFIX + userId + ":" + conversationId;
    }
}
