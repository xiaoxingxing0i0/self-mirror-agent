package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 任务表 task（docs/data_model.md §4.2.5）。
 */
@Data
@TableName("task")
public class Task {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联用户 ID */
    private Long userId;

    /** 任务类型：report/statistic/profile_update */
    private String taskType;

    /** 任务名称 */
    private String taskName;

    /** 任务参数（JSON 字符串） */
    private String taskParams;

    /** 状态：0 待执行 1 执行中 2 已完成 3 失败 4 已取消 */
    private Integer status;

    /** 周期任务 Cron 表达式 */
    private String cronExpression;

    /** 任务进度 0-100 */
    private Integer progress;

    /** 关联结果 ID（如报告 ID） */
    private Long resultId;

    /** 失败错误信息 */
    private String errorMsg;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    public static final String TYPE_REPORT = "report";
    public static final String TYPE_STATISTIC = "statistic";
    public static final String TYPE_PROFILE_UPDATE = "profile_update";
}
