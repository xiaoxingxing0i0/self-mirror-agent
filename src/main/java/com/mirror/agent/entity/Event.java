package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人生事件表 event（情绪-事件关联，用户级数据）。
 * 事件同一性 =（时段 + 参与者 + 主题）三元组。
 * 摘要为「事实归并的派生缓存」：建档时 bootstrap（名称+初始事实），
 * 封稿/追加提及后由全部增量事实（event_mention.fact_content）一次性归并生成，中途不逐轮改写（防漂移）。
 */
@Data
@TableName("event")
public class Event {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    /** 事件名称（≤25字短语） */
    private String name;

    /** 事件现状摘要（≤300字，事实归并生成，可重建的派生缓存） */
    private String summary;

    /** 状态：0 EMERGING 增量叙述中 1 SETTLED 已定稿 */
    private Integer status;

    /** 核心要素JSON：time/participants/topic */
    private String coreFacts;

    /** 最近携带情绪码JSON（冗余，明细在 event_mention） */
    private String emotionProfile;

    private Integer mentionCount;

    /** 重提次数（episode 语义） */
    private Integer rekindleCount;

    private LocalDateTime firstMentionedAt;

    private LocalDateTime lastMentionedAt;

    /** 最近活跃时间（rerank 时间衰减依据） */
    private LocalDateTime lastActiveTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;

    public static final int STATUS_EMERGING = 0;
    public static final int STATUS_SETTLED = 1;
}
