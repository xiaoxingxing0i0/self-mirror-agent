package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户画像表 user_profile（docs/data_model.md §4.2.7）。
 * JSON 字段以字符串承载，序列化由 service 层负责。
 */
@Data
@TableName("user_profile")
public class UserProfile {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联用户 ID */
    private Long userId;

    /** 画像版本号 */
    private Integer version;

    /** 统计截止日期 */
    private LocalDate statDate;

    /** 情绪特征数据（JSON） */
    private String emotionFeature;

    /** 心理特征数据（JSON） */
    private String psychologyFeature;

    /** 行为模式数据（JSON） */
    private String behaviorFeature;

    /** 样本消息数量 */
    private Integer sampleCount;

    private LocalDateTime createTime;
}
