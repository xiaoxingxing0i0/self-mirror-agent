package com.mirror.agent.report.contract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.List;

/**
 * PDF 报告契约 DTO：由 {@link ContractValidator} 校验通过后从 JSON 反序列化而来。
 *
 * <p>字段与 {@code resources/report-schema/report.schema.json} 一一对应；
 * 未知字段在反序列化时被忽略（校验阶段已由 schema 的 additionalProperties:false 拦截）。</p>
 *
 * @param reportId    报告唯一标识
 * @param title       报告标题
 * @param generatedAt 生成时间（UTC 瞬时，展示层按时区格式化）
 * @param summary     核心摘要，可为 null
 * @param sections    章节列表，可为 null 或空
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportContract(
        String reportId,
        String title,
        Instant generatedAt,
        String summary,
        List<Section> sections) {

    /**
     * 报告章节。
     *
     * @param heading 章节标题
     * @param rows    数据行，可为 null 或空
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Section(String heading, List<Row> rows) {
    }

    /**
     * 章节数据行：label/value/status 三列。
     *
     * @param label  指标名
     * @param value  指标值，可为 null（组装层归一为空串）
     * @param status 状态，可为 null
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(String label, String value, Status status) {
    }

    /**
     * 行状态枚举。展示名不依赖 {@link #toString()} / name()，由组装层提供稳定映射，
     * 新增枚举值时<b>必须</b>同步扩展 {@code ReportModelAssembler#statusLabel}。
     */
    public enum Status {
        /** 正常 */
        OK,
        /** 注意 */
        WARN,
        /** 严重 */
        CRITICAL
    }
}
