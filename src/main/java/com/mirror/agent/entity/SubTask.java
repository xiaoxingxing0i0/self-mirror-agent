package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 子任务表 sub_task（docs/data_model.md §4.2.6）。
 */
@Data
@TableName("sub_task")
public class SubTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联主任务 ID */
    private Long taskId;

    /** 执行顺序 */
    private Integer stepOrder;

    /** 步骤名称 */
    private String stepName;

    /** 状态：0 待执行 1 执行中 2 已完成 3 失败 */
    private Integer status;

    /** 已重试次数 */
    private Integer retryCount;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
