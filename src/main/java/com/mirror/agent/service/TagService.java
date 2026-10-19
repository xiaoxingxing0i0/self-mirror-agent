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
import java.util.List;

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
     * 回溯归因：把先前「说了情绪未说事由」的历史消息标签行归因到本次事件
     * （如先说「我好伤心啊」、后说事件原委的场景）。
     * 仅 emotion/psychology 类且未归因的行可被回填；user_id 隔离防越权。
     */
    public int backfillTagsToEvent(Long userId, List<Long> messageIds, Long eventId) {
        if (messageIds == null || messageIds.isEmpty()) {
            return 0;
        }
        return tagRecordMapper.update(null, Wrappers.<TagRecord>lambdaUpdate()
                .set(TagRecord::getEventId, eventId)
                .eq(TagRecord::getUserId, userId)
                .in(TagRecord::getMessageId, messageIds)
                .in(TagRecord::getTagCategory,
                        List.of(TagItem.CATEGORY_EMOTION, TagItem.CATEGORY_PSYCHOLOGY))
                .isNull(TagRecord::getEventId));
    }
}
