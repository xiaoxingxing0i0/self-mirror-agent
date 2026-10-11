package com.mirror.agent.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 合规检查 Agent 输出（docs/agent_definition.md §2.6）。
 */
@Data
public class ComplianceResult implements Serializable {

    /** 违规级别与分级处理策略 */
    public enum ViolationLevel {
        /** 合规通过 */
        NONE,
        /** 轻度：返回修改意见，触发对应 Agent 重生成 */
        MILD,
        /** 中度：拦截输出，返回标准化兜底回复 */
        MODERATE,
        /** 重度（自伤/伤人风险）：立即拦截，输出正向干预话术并记录风险日志 */
        SEVERE
    }

    /** 文本类型：INPUT / OUTPUT / REPORT */
    public enum TextType {
        INPUT, OUTPUT, REPORT
    }

    /** 是否通过 */
    private boolean passed;

    /** 违规级别 */
    private ViolationLevel level = ViolationLevel.NONE;

    /** 违规内容类型（敏感词 / 诊断表述 / 高危风险等） */
    private String violationType;

    /** 违规内容位置（命中的词或片段） */
    private String violationPosition;

    /** 修改优化建议（引导重生成） */
    private String suggestion;

    /** 中/重度违规时直接返回给用户的兜底或正向干预话术 */
    private String interceptedReply;

    public static ComplianceResult pass() {
        ComplianceResult result = new ComplianceResult();
        result.setPassed(true);
        return result;
    }

    public static ComplianceResult reject(ViolationLevel level, String type, String position, String reply) {
        ComplianceResult result = new ComplianceResult();
        result.setPassed(false);
        result.setLevel(level);
        result.setViolationType(type);
        result.setViolationPosition(position);
        result.setInterceptedReply(reply);
        return result;
    }
}
