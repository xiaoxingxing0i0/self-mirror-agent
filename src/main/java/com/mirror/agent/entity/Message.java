package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话消息表 message（docs/data_model.md §4.2.3）。
 */
@Data
@TableName("message")
public class Message {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联会话 ID */
    private Long conversationId;

    /** 关联用户 ID */
    private Long userId;

    /** 角色：0 用户 1 系统 */
    private Integer role;

    /** 消息内容原文 */
    private String content;

    /** 关联 Milvus 向量 ID */
    private String vectorId;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;

    public static final int ROLE_USER = 0;
    public static final int ROLE_SYSTEM = 1;
}
