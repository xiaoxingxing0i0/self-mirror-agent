package com.mirror.agent.dp.scheduler;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * EMERGING 事由定稿定时器（Redis ZSET mirror:incident:finalize，score=定稿到期毫秒）。
 * 每次事由有新增提及都 touch 续期；到期由 {@link IncidentDraftFinalizer} 原子认领定稿。
 */
@Component
@RequiredArgsConstructor
public class IncidentFinalizeQueue {

    private static final String QUEUE_KEY = "mirror:incident:finalize";

    private final StringRedisTemplate stringRedisTemplate;

    /** 续期定稿时间：now + finalizeMinutes 后到期 */
    public void touch(Long incidentId, int finalizeMinutes) {
        stringRedisTemplate.opsForZSet().add(QUEUE_KEY, String.valueOf(incidentId),
                System.currentTimeMillis() + finalizeMinutes * 60_000L);
    }

    /** 已到定稿时间的事由 ID（按到期升序） */
    public Set<String> due(int limit) {
        Set<String> due = stringRedisTemplate.opsForZSet()
                .rangeByScore(QUEUE_KEY, 0, System.currentTimeMillis(), 0, limit);
        return due == null ? Set.of() : due;
    }

    /** 原子认领（多实例互斥）。ZSetOperations.remove 返回 Long=实际移除数 */
    public boolean claim(Long incidentId) {
        Long removed = stringRedisTemplate.opsForZSet()
                .remove(QUEUE_KEY, String.valueOf(incidentId));
        return removed != null && removed > 0;
    }
}
