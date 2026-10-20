package com.mirror.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mirror.agent.entity.TagRecord;
import com.mirror.agent.message.TagItem;
import com.mirror.agent.repository.TagRecordMapper;
import com.mirror.agent.repository.TagRecordMapper.EmotionTrendStat;
import com.mirror.agent.repository.TagRecordMapper.TagStat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 标签记录服务：写入语言分析标签、提供画像 / 报告聚合统计（全部可溯源到消息 ID）。
 */
@Service
@RequiredArgsConstructor
public class TagService {

    /** 画像 / 报告统计仅采用置信度达标的正式标签 */
    public static final double PROFILE_MIN_CONFIDENCE = 0.7;

    private final TagRecordMapper tagRecordMapper;

    public void saveTags(Long messageId, Long userId, List<TagItem> tags) {
        if (tags == null || tags.isEmpty()) {
            return;
        }
        for (TagItem item : tags) {
            TagRecord record = new TagRecord();
            record.setMessageId(messageId);
            record.setUserId(userId);
            record.setTagCategory(item.getCategory());
            record.setTagCode(item.getCode());
            record.setTagName(item.getName());
            record.setConfidence(BigDecimal.valueOf(item.getConfidence())
                    .setScale(2, RoundingMode.HALF_UP));
            record.setCreateTime(LocalDateTime.now());
            tagRecordMapper.insert(record);
        }
    }

    public List<TagRecord> listByMessage(Long messageId) {
        return tagRecordMapper.selectList(Wrappers.<TagRecord>lambdaQuery()
                .eq(TagRecord::getMessageId, messageId));
    }

    /** 时间范围内正式标签频次统计（待确认标签不计入画像） */
    public List<TagStat> statByRange(Long userId, LocalDateTime start, LocalDateTime end) {
        return tagRecordMapper.statByUserAndRange(userId, start, end, PROFILE_MIN_CONFIDENCE);
    }

    public List<EmotionTrendStat> statEmotionTrend(Long userId, LocalDateTime start, LocalDateTime end) {
        return tagRecordMapper.statEmotionTrend(userId, start, end);
    }

    /**
     * 本轮消息的事件归因回填：仅更新指定消息中命中这些标签码且尚未归因的行。
     * user_id 条件保证跨用户 id 伪造无效；event_id IS NULL 保证不覆盖已归因行。
     */
    public int fillTagsByCodes(Long userId, List<Long> messageIds, Long eventId, List<String> tagCodes) {
        if (messageIds == null || messageIds.isEmpty() || tagCodes == null || tagCodes.isEmpty()) {
            return 0;
        }
        return tagRecordMapper.update(null, Wrappers.<TagRecord>lambdaUpdate()
                .set(TagRecord::getEventId, eventId)
                .eq(TagRecord::getUserId, userId)
                .in(TagRecord::getMessageId, messageIds)
                .in(TagRecord::getTagCode, tagCodes)
                .isNull(TagRecord::getEventId));
    }

    /**
     * 情绪挂起队列绑定（事件链路 v2，替代模型回溯指认）：
     * 事件落库时，把时间窗口内「只说情绪未说事由」的未归因标签行关联到本事件。
     * 绑定规则（确定性，零模型调用）：
     * ① 情绪码与本事件携带码有交集的行 → 绑定；
     * ② 无交集 → 仅绑定「最近且最强」的一条（时间+置信度排序首位），其余留在队列待后续事件或过期；
     * ③ 本轮消息已按码归因（fillTagsByCodes），通过 excludeMessageIds 排除，不重复处理。
     * 过期未绑定 = 超出窗口仍未归因的行，天然可查（event_id IS NULL），无需转储。
     *
     * @return 本次绑定的标签行数
     */
    public int bindPendingEmotions(Long userId, Long eventId, List<String> eventTagCodes,
                                   List<Long> excludeMessageIds, int windowMinutes) {
        if (userId == null || eventId == null) {
            return 0;
        }
        List<TagRecord> candidates = tagRecordMapper.selectList(Wrappers.<TagRecord>lambdaQuery()
                .eq(TagRecord::getUserId, userId)
                .in(TagRecord::getTagCategory,
                        List.of(TagItem.CATEGORY_EMOTION, TagItem.CATEGORY_PSYCHOLOGY))
                .isNull(TagRecord::getEventId)
                .ge(TagRecord::getCreateTime, LocalDateTime.now().minusMinutes(windowMinutes))
                .notIn(excludeMessageIds != null && !excludeMessageIds.isEmpty(),
                        TagRecord::getMessageId, excludeMessageIds == null ? List.of() : excludeMessageIds)
                .orderByDesc(TagRecord::getCreateTime)
                .orderByDesc(TagRecord::getConfidence));
        if (candidates.isEmpty()) {
            return 0;
        }
        Set<String> codes = new HashSet<>(
                eventTagCodes == null ? List.of() : eventTagCodes);
        List<Long> toBind = new ArrayList<>();
        for (TagRecord candidate : candidates) {
            if (codes.contains(candidate.getTagCode())) {
                toBind.add(candidate.getId());
            }
        }
        if (toBind.isEmpty() && !codes.isEmpty()) {
            // 事件携带情绪码但无交集：绑定「最近且最强」一条（列表已按时间+置信度倒序）
            toBind.add(candidates.get(0).getId());
        } else if (toBind.isEmpty() && candidates.size() == 1) {
            // 无情绪纯事件：仅当队列只有唯一 pending 时直绑（多条挂起不猜归属）
            toBind.add(candidates.get(0).getId());
        }
        if (toBind.isEmpty()) {
            return 0;
        }
        tagRecordMapper.update(null, Wrappers.<TagRecord>lambdaUpdate()
                .set(TagRecord::getEventId, eventId)
                .in(TagRecord::getId, toBind));
        return toBind.size();
    }

    /**
     * 窗口内仍未归因的情绪标签名（封稿时的 pre_event_emotion 提示素材：
     * 「用户描述本事件前曾表达X，可能相关但未确认」，仅谨慎提及不断言因果）。
     */
    public List<String> listPendingEmotionNames(Long userId, LocalDateTime from, LocalDateTime to) {
        if (userId == null || from == null || to == null || !to.isAfter(from)) {
            return List.of();
        }
        List<TagRecord> rows = tagRecordMapper.selectList(Wrappers.<TagRecord>lambdaQuery()
                .eq(TagRecord::getUserId, userId)
                .eq(TagRecord::getTagCategory, TagItem.CATEGORY_EMOTION)
                .isNull(TagRecord::getEventId)
                .between(TagRecord::getCreateTime, from, to));
        return rows.stream().map(TagRecord::getTagName).distinct().toList();
    }

    /** 拆分通道连带迁移：指定消息上归因到源事件的标签行改挂到拆出的新事件 */
    public int reattributeTagsToEvent(Long userId, Long fromEventId, Long toEventId,
                                      List<Long> messageIds) {
        if (userId == null || fromEventId == null || toEventId == null
                || messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        return tagRecordMapper.update(null, Wrappers.<TagRecord>lambdaUpdate()
                .set(TagRecord::getEventId, toEventId)
                .eq(TagRecord::getUserId, userId)
                .eq(TagRecord::getEventId, fromEventId)
                .in(TagRecord::getMessageId, messageIds));
    }

    /**
     * 用户纠正解绑（「不是因为这个」类信号）：把指定消息归因到指定事件的情绪/心理行放回挂起队列。
     * 仅 emotion/psychology 类，intent/task 归因不动。
     */
    public int unbindEmotionsFromEvent(Long userId, Long eventId, Long messageId) {
        if (userId == null || eventId == null || messageId == null) {
            return 0;
        }
        return tagRecordMapper.update(null, Wrappers.<TagRecord>lambdaUpdate()
                .set(TagRecord::getEventId, null)
                .eq(TagRecord::getUserId, userId)
                .eq(TagRecord::getEventId, eventId)
                .eq(TagRecord::getMessageId, messageId)
                .in(TagRecord::getTagCategory,
                        List.of(TagItem.CATEGORY_EMOTION, TagItem.CATEGORY_PSYCHOLOGY)));
    }
}
