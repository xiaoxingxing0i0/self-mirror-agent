package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件提及明细 event_mention。
 * 「重提次数/时间点」= mention_type=REKINDLED 的行；「事件情绪次数」= emotion_codes 聚合。
 * conversation_id 仅溯源用（当前产品形态一人一会话），不参与任何逻辑分支。
 */
@Data
@TableName("event_mention")
public class EventMention {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long eventId;

    private Long userId;

    /** 触发提及的用户消息 ID（溯源硬约束） */
    private Long messageId;

    private Long conversationId;

    /** 0 NEW 1 CONTINUATION 2 REKINDLED */
    private Integer mentionType;

    /** 本次携带情绪码 JSON 数组（可为空=无情绪事件） */
    private String emotionCodes;

    /** 本次携带心理特征码 JSON 数组 */
    private String psychologyCodes;

    private Double confidence;

    private LocalDateTime createTime;

    public static final int TYPE_NEW = 0;
    public static final int TYPE_CONTINUATION = 1;
    public static final int TYPE_REKINDLED = 2;
}
