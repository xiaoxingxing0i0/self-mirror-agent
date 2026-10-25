package com.mirror.agent.message;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 语言分析 Agent 结构化输出（JSON Schema 约束）。
 */
@Data
public class LanguageAnalysisResult implements Serializable {

    /** 结构化标签集：情绪 / 意图 / 心理 / 任务 */
    private List<TagItem> tags = new ArrayList<>();

    /** 语义核心摘要 */
    private String summary;

    /** 主意图（DP 调度路由依据）：chat / consult / query / task */
    private String primaryIntent;

    /** 是否存在待确认（低置信度）标签，需引导用户澄清 */
    private boolean needClarify;

    /** 输入句子中途截断、需等用户续说才能完整理解（碎片归集判定信号） */
    private boolean fragment;

    /** 本轮提及的事由草案（0..N 个；情绪挂各事由对象内；空=无事由，泛化情绪不建事由） */
    private List<IncidentDraft> incidents = new ArrayList<>();

    /**
     * 查询指路：用户在问【最近提及事由】中的已知事由时列出其 #id（query/画像类问题，可多个）。
     * 只允许列表内真实 id；不在列表或事由不涉及 → 空（IntentRouter 据此决定事由档案直检还是回落消息向量检索）。
     */
    private List<Long> queryIncidentIds = new ArrayList<>();

    /** 报告周期锚点（仅任务分支消费；用户未提时间→null 或 explicit=false，解析与日历运算收敛于 ReportPeriodResolver） */
    private PeriodHint periodHint;

    /**
     * 报告周期锚点描述——模型只做「分类 + 照抄字面日期」，不做任何日历推算；
     * type→区间的换算、钳制与降级全部在 ReportPeriodResolver（确定性层）。
     */
    @Data
    public static class PeriodHint implements Serializable {

        /**
         * 锚点类型：TODAY | YESTERDAY | THIS_WEEK | LAST_WEEK | THIS_MONTH | LAST_MONTH |
         * LAST_N_DAYS | ABS_DATE | ABS_MONTH | ABS_RANGE | INCIDENT | LAST_REPORT_END | ALL_TIME；
         * 未知值由 resolver 归入默认窗口降级。
         */
        private String type;

        /** ABS_MONTH / ABS_DATE：用户明示的年月日；未说年份→year=null 由 resolver 按「未到→取去年」补 */
        private Integer year;
        private Integer month;
        private Integer day;

        /** ABS_RANGE：照抄用户原话的字面日期（YYYY-MM-DD），严禁模型换算 */
        private String start;
        private String end;

        /** LAST_N_DAYS：近 n 天 */
        private Integer n;

        /** INCIDENT：围绕【最近提及事由】中某件事建报告时的真实 #id（列表外 id 由归属复验拦截） */
        private Long incidentRef;

        /** 用户是否明示了时间范围；false/缺失=没说，其余字段必须为空，由调用方走默认窗口或澄清 */
        private Boolean explicit;
    }

    public List<TagItem> tagsOf(String category) {
        return tags.stream().filter(t -> category.equals(t.getCategory())).toList();
    }

    public String firstTagCode(String category) {
        return tags.stream()
                .filter(t -> category.equals(t.getCategory()))
                .map(TagItem::getCode)
                .findFirst()
                .orElse(null);
    }
}
