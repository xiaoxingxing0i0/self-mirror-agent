package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 分析报告表 analysis_report（docs/data_model.md §4.2.8）。
 */
@Data
@TableName("analysis_report")
public class AnalysisReport {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联用户 ID */
    private Long userId;

    /** 类型：daily/weekly/monthly/custom */
    private String reportType;

    /** 报告标题 */
    private String reportTitle;

    /** 统计开始日期 */
    private LocalDate startDate;

    /** 统计结束日期 */
    private LocalDate endDate;

    /** 报告核心摘要 */
    private String summary;

    /** 报告完整内容（Markdown） */
    private String content;

    /** 统计指标数据 JSON（供前端 ECharts 渲染） */
    private String statData;

    /** 关联任务 ID */
    private Long taskId;

    private LocalDateTime createTime;

    @TableLogic
    private Integer isDeleted;

    public static final String TYPE_DAILY = "daily";
    public static final String TYPE_WEEKLY = "weekly";
    public static final String TYPE_MONTHLY = "monthly";
    public static final String TYPE_CUSTOM = "custom";
}
