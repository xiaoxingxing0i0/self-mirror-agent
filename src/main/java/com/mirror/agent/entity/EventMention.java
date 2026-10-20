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

    /**
     * 本次提及贡献的增量事实 JSON 数组（稳定层，只追加不改写）。
     * 现状摘要由封稿时对本事件全部 mention 的 fact_content 归并生成，非本字段承载。
     */
    private String factContent;

    /** merge-bias 存疑标记：1=本条为「拿不准先归入」（AMBIGUOUS），待封稿复核，可被拆分 */
    private Integer ambiguous;

    private Double confidence;

    private LocalDateTime createTime;

    public static final int TYPE_NEW = 0;
    public static final int TYPE_CONTINUATION = 1;
    public static final int TYPE_REKINDLED = 2;

    public static final int AMBIGUOUS_NO = 0;
    public static final int AMBIGUOUS_YES = 1;
}
