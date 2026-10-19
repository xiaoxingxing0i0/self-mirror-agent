package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 标签记录表 tag_record（docs/data_model.md §4.2.4）。
 */
@Data
@TableName("tag_record")
public class TagRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联消息 ID */
    private Long messageId;

    /** 关联用户 ID */
    private Long userId;

    /** 标签大类：emotion/intent/psychology/task */
    private String tagCategory;

    /** 标签编码 */
    private String tagCode;

    /** 标签名称 */
    private String tagName;

    /** 置信度 0-1 */
    private BigDecimal confidence;

    /** 关联事件 ID（情绪-事件归因，事件链路异步回填；NULL=未归因/无事件情绪） */
    private Long eventId;

    private LocalDateTime createTime;
}
