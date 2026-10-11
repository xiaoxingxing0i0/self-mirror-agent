package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.Message;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话消息 Mapper，附带行为模式统计（画像「活跃时段」维度）。
 */
@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /** 按小时统计用户活跃分布（仅统计用户发出的消息） */
    @Select("""
            SELECT HOUR(create_time) AS hourOfDay, COUNT(*) AS cnt
            FROM message
            WHERE user_id = #{userId} AND role = 0 AND is_deleted = 0
              AND create_time >= #{start} AND create_time < #{end}
            GROUP BY hourOfDay
            ORDER BY cnt DESC
            """)
    List<HourStat> statActiveHours(@Param("userId") Long userId,
                                   @Param("start") LocalDateTime start,
                                   @Param("end") LocalDateTime end);

    @Select("""
            SELECT COUNT(*) FROM message
            WHERE user_id = #{userId} AND role = 0 AND is_deleted = 0
              AND create_time >= #{start} AND create_time < #{end}
            """)
    long countUserMessages(@Param("userId") Long userId,
                           @Param("start") LocalDateTime start,
                           @Param("end") LocalDateTime end);

    /** 活跃时段统计投影 */
    @Data
    class HourStat {
        private Integer hourOfDay;
        private Long cnt;
    }
}
