package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话表 conversation（docs/data_model.md §4.2.2）。
 */
@Data
@TableName("conversation")
public class Conversation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联用户 ID */
    private Long userId;

    /** 会话标题（自动生成） */
    private String title;

    private LocalDateTime startTime;

    private LocalDateTime lastActiveTime;

    /** 会话状态：0 进行中 1 已结束 */
    private Integer status;

    /** 消息总数 */
    private Integer messageCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
