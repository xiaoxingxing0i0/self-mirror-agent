package com.mirror.agent.dp.scheduler;

import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.message.FragmentBuffer;
import com.mirror.agent.message.FragmentBuffer.Fragment;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 碎片归集存储（Redis）：
 * 缓冲 mirror:agg:buf:{conversationId}（JSON）+ 全局截止时间延迟队列 mirror:agg:deadline
 * （ZSET，score=截止毫秒）。定时器在 Redis 上：服务重启不丢；ZREM 原子认领保证多实例仅一个触发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageAggregator {

    private static final String BUFFER_PREFIX = "mirror:agg:buf:";
    private static final String DEADLINE_KEY = "mirror:agg:deadline";
    /** 缓冲键在截止时间基础上附加的冗余 TTL，防止认领后处理异常导致键残留 */
    private static final Duration BUFFER_REDUNDANCY = Duration.ofMinutes(5);

    private final RedisTemplate<String, Object> redisTemplate;
    private final StringRedisTemplate stringRedisTemplate;
    private final AgentProperties agentProperties;

    /** 该会话是否存在进行中的归集窗口 */
    public boolean hasPending(Long conversationId) {
        return stringRedisTemplate.opsForZSet()
                .score(DEADLINE_KEY, String.valueOf(conversationId)) != null;
    }

    /** 原子认领归集定时器（ZREM）：多实例/多线程仅一个调用方成功 */
    public boolean claim(Long conversationId) {
        return Boolean.TRUE.equals(stringRedisTemplate.opsForZSet()
                .remove(DEADLINE_KEY, String.valueOf(conversationId)));
    }

    /** 写入/刷新缓冲与滑动截止时间：deadline = min(now+窗口, 首碎片时间+总上限)，返回 deadline 毫秒 */
    public long rebuffer(Long conversationId, FragmentBuffer buffer) {
        long now = System.currentTimeMillis();
        if (buffer.getFirstTs() <= 0) {
            buffer.setFirstTs(now);
        }
        long deadline = Math.min(now + windowMillis(), buffer.getFirstTs() + totalWaitMillis());
        Duration ttl = Duration.ofMillis(Math.max(deadline - now, 1000L)).plus(BUFFER_REDUNDANCY);
        redisTemplate.opsForValue().set(BUFFER_PREFIX + conversationId, buffer, ttl);
        stringRedisTemplate.opsForZSet().add(DEADLINE_KEY, String.valueOf(conversationId), deadline);
        return deadline;
    }

    /** 取出缓冲并删除键（须在 claim 成功后调用） */
    public FragmentBuffer takeBuffer(Long conversationId) {
        Object cached = redisTemplate.opsForValue().get(BUFFER_PREFIX + conversationId);
        redisTemplate.delete(BUFFER_PREFIX + conversationId);
        return cached instanceof FragmentBuffer buffer ? buffer : null;
    }

    /** 清理缓冲与定时器（强制合并后调用） */
    public void clear(Long conversationId) {
        redisTemplate.delete(BUFFER_PREFIX + conversationId);
        claim(conversationId);
    }

    /** 到期（含过期未认领）的会话 ID 集合，按截止时间升序 */
    public Set<String> dueConversations(int limit) {
        Set<String> due = stringRedisTemplate.opsForZSet()
                .rangeByScore(DEADLINE_KEY, 0, System.currentTimeMillis(), 0, limit);
        return due == null ? Set.of() : due;
    }

    /** 是否超出归集上限（数量 / 自首碎片的总等待时长） */
    public boolean overflowed(long firstTs, int fragmentCount) {
        if (fragmentCount > agentProperties.getFragmentMaxFragments()) {
            return true;
        }
        return System.currentTimeMillis() - firstTs >= totalWaitMillis();
    }

    /** 按序合并碎片文本：碎片间缺标点时以「，」衔接，保持口语断句自然 */
    public String mergeText(List<Fragment> fragments) {
        StringBuilder merged = new StringBuilder();
        for (Fragment fragment : fragments) {
            appendPart(merged, fragment.getContent());
        }
        return merged.toString();
    }

    private void appendPart(StringBuilder merged, String part) {
        String text = part == null ? "" : part.strip();
        if (text.isEmpty()) {
            return;
        }
        if (merged.length() > 0 && !endsWithPunctuation(merged)) {
            merged.append('，');
        }
        merged.append(text);
    }

    private boolean endsWithPunctuation(StringBuilder merged) {
        char last = merged.charAt(merged.length() - 1);
        return "，,、；;：:。.！!?？…~".indexOf(last) >= 0;
    }

    private long windowMillis() {
        return agentProperties.getFragmentWindowSeconds() * 1000L;
    }

    private long totalWaitMillis() {
        return agentProperties.getFragmentMaxTotalWaitSeconds() * 1000L;
    }
}
