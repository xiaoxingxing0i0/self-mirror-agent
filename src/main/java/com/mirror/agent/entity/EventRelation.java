package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 事件间引用关系边 event_relation（事件链路 v2）。
 * 只承载「不同事件」的引用（对比/因果/单纯提及）；
 * 同一事件的延续与重提走 event_mention（CONTINUATION/REKINDLED），不进本表。
 * 双向可查：正向按 event_id 看本事件引用了谁，反向按 ref_event_id 看谁引用了本事件。
 */
@Data
@TableName("event_relation")
public class EventRelation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 主动引用方事件 ID */
    private Long eventId;

    /** 被引用的旧事件 ID */
    private Long refEventId;

    /** 归属用户 ID（与事件同维度隔离） */
    private Long userId;

    /** 引用类型：comparison 对比 | causal 因果 | mention 单纯提及 */
    private String refType;

    /** 引用原文片段（如「比上次好」，可溯源） */
    private String refText;

    /** 建立该引用关系的触发消息 ID（溯源） */
    private Long evidenceMessageId;

    private LocalDateTime createTime;

    public static final String TYPE_COMPARISON = "comparison";
    public static final String TYPE_CAUSAL = "causal";
    public static final String TYPE_MENTION = "mention";
}
