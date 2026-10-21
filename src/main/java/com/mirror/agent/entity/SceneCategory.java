package com.mirror.agent.entity;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 事由场景分类（19 类固定枚举）：每次事由现状摘要归并时由 LLM 判定，
 * 中文标签为唯一对外表示（MySQL {@code incident.scene_category} 与 Milvus {@code scene_category} 均存 label）。
 *
 * <p>建档 bootstrap 摘要<b>不</b>判定本分类（保持 NULL），封稿/刷新时写入。</p>
 */
public enum SceneCategory {

    /** 生存安全 */
    SURVIVAL_SAFETY("生存安全"),
    /** 工作职业 */
    WORK_CAREER("工作职业"),
    /** 学习成长 */
    STUDY_GROWTH("学习成长"),
    /** 家庭亲子 */
    FAMILY_PARENTING("家庭亲子"),
    /** 亲密关系 */
    INTIMATE_RELATIONSHIP("亲密关系"),
    /** 社交人际 */
    SOCIAL_INTERPERSONAL("社交人际"),
    /** 经济财务 */
    FINANCIAL_ECONOMIC("经济财务"),
    /** 身心健康 */
    HEALTH_MENTAL("身心健康"),
    /** 居住环境 */
    LIVING_ENVIRONMENT("居住环境"),
    /** 休闲兴趣 */
    LEISURE_INTEREST("休闲兴趣"),
    /** 自我实现 */
    SELF_ACTUALIZATION("自我实现"),
    /** 社会文化 */
    SOCIETY_CULTURE("社会文化"),
    /** 法律安全 */
    LEGAL_SAFETY("法律安全"),
    /** 科技信息 */
    TECH_INFORMATION("科技信息"),
    /** 时间人生 */
    TIME_LIFE("时间人生"),
    /** 意外丧失 */
    UNEXPECTED_LOSS("意外丧失"),
    /** 信仰意义 */
    FAITH_MEANING("信仰意义"),
    /** 照护责任 */
    CAREGIVING_DUTY("照护责任"),
    /** 兜底：无法明确归类 */
    OTHER("其他");

    private final String label;

    SceneCategory(String label) {
        this.label = label;
    }

    /**
     * @return 中文标签（存储值，与 prompt 选项一致）
     */
    public String label() {
        return label;
    }

    /**
     * @return 全部 19 个中文标签（prompt 注入用，{@code 、} 分隔）
     */
    public static String joinedLabels() {
        return Arrays.stream(values()).map(SceneCategory::label)
                .collect(Collectors.joining("、"));
    }

    /**
     * 精确解析（trim 后全等匹配，不做模糊归一——模糊匹配交给 prompt 约束，代码只认白名单）。
     *
     * @param raw 原始输入（可空）
     * @return 匹配的枚举；不在白名单或空白返回 null
     */
    public static SceneCategory fromLabel(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        for (SceneCategory value : values()) {
            if (value.label.equals(trimmed)) {
                return value;
            }
        }
        return null;
    }

    /**
     * LLM 输出的场景值归一：
     * 命中白名单 → 原标签；非空但不在白名单 → 「其他」（模型声称已选但越界，保守兜底）；空白 → null（缺失，调用方保留原值）。
     *
     * @param raw LLM 输出的 scene 字段
     * @return 规范化中文标签或 null
     */
    public static String normalizeLabel(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        SceneCategory matched = fromLabel(raw);
        return matched == null ? OTHER.label : matched.label;
    }

    /**
     * 断言/测试辅助：标签列表视图。
     */
    public static List<String> labels() {
        return Arrays.stream(values()).map(SceneCategory::label).toList();
    }
}
