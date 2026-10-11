package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.TagRecord;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 标签记录 Mapper，附带画像 / 报告所需的聚合统计查询。
 */
@Mapper
public interface TagRecordMapper extends BaseMapper<TagRecord> {

    /**
     * 按用户与时间范围聚合标签频次（画像构建、报告统计的数据来源，保证结论可溯源）。
     */
    @Select("""
            SELECT tag_category AS tagCategory, tag_code AS tagCode,
                   tag_name AS tagName, COUNT(*) AS cnt
            FROM tag_record
            WHERE user_id = #{userId}
              AND create_time >= #{start} AND create_time < #{end}
              AND confidence >= #{minConfidence}
            GROUP BY tag_category, tag_code, tag_name
            ORDER BY cnt DESC
            """)
    List<TagStat> statByUserAndRange(@Param("userId") Long userId,
                                     @Param("start") LocalDateTime start,
                                     @Param("end") LocalDateTime end,
                                     @Param("minConfidence") double minConfidence);

    /**
     * 按天聚合情绪标签（报告「情绪走势」模块的数据来源）。
     */
    @Select("""
            SELECT DATE_FORMAT(create_time, '%Y-%m-%d') AS statDay,
                   tag_name AS tagName, COUNT(*) AS cnt
            FROM tag_record
            WHERE user_id = #{userId} AND tag_category = 'emotion'
              AND create_time >= #{start} AND create_time < #{end}
            GROUP BY statDay, tagName
            ORDER BY statDay ASC, cnt DESC
            """)
    List<EmotionTrendStat> statEmotionTrend(@Param("userId") Long userId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    /** 标签频次统计投影 */
    @Data
    class TagStat {
        private String tagCategory;
        private String tagCode;
        private String tagName;
        private Long cnt;
    }

    /** 情绪走势统计投影 */
    @Data
    class EmotionTrendStat {
        private String statDay;
        private String tagName;
        private Long cnt;
    }
}
