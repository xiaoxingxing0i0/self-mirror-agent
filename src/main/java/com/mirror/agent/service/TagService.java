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
}
