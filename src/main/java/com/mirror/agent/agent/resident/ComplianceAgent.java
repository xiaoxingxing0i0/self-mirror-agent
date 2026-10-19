package com.mirror.agent.agent.resident;

import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.ComplianceProperties;
import com.mirror.agent.llm.AgentRole;
import com.mirror.agent.llm.LlmGateway;
import com.mirror.agent.llm.LlmRequest;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.ComplianceResult;
import com.mirror.agent.message.ComplianceResult.TextType;
import com.mirror.agent.message.ComplianceResult.ViolationLevel;
import com.mirror.agent.tool.SensitiveWordFilter;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 合规检查 Agent（docs/agent_definition.md §2.6）。
 * 两级校验：本地敏感词 DFA（毫秒级）+ 大模型语义合规（心理场景专项）。
 * 硬性约束：拦截一切临床诊断 / 医疗建议表述；高危风险触发正向干预。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ComplianceAgent {

    /** 风险拦截留痕日志（独立 logger，可单独落盘审计） */
    private static final Logger RISK_LOG = LoggerFactory.getLogger("RISK_AUDIT");

    /** 重度违规（自伤/伤人）正向干预话术 */
    public static final String SEVERE_INTERVENTION =
            "我能感受到你此刻可能正经历非常痛苦的情绪。你的感受很重要，你并不孤单。"
                    + "请现在就联系一位你信任的家人或朋友聊聊，也可以拨打心理援助热线（如全国心理援助热线 12356），"
                    + "专业的支持能够帮助你度过这个时刻。我会一直在这里陪着你。";

    /** 中度违规标准化兜底回复 */
    public static final String MODERATE_FALLBACK =
            "抱歉，这部分内容我暂时无法回应。我可以为你提供情绪陪伴与心理健康科普信息，"
                    + "但无法提供医疗诊断或建议。我们聊聊别的吧——你最近的感受怎么样？";

    private final SensitiveWordFilter sensitiveWordFilter;
    private final LlmGateway llmGateway;
    private final PromptUtil promptUtil;
    private final ComplianceProperties complianceProperties;

    public AgentResult<ComplianceResult> checkInput(String text) {
        return check(text, TextType.INPUT);
    }

    public AgentResult<ComplianceResult> checkOutput(String text) {
        return check(text, TextType.OUTPUT);
    }

    public AgentResult<ComplianceResult> checkReport(String text) {
        return check(text, TextType.REPORT);
    }

    private AgentResult<ComplianceResult> check(String text, TextType type) {
        try {
            ComplianceResult dictResult = checkByDict(text, type);
            if (!dictResult.isPassed()) {
                return AgentResult.ok(dictResult);
            }
            return AgentResult.ok(checkByLlm(text, type));
        } catch (Exception e) {
            log.error("合规校验异常: type={}", type, e);
            return AgentResult.fail(ErrorCode.SYSTEM_ERROR, "合规校验异常: " + e.getMessage());
        }
    }

    /** 一级校验：本地敏感词（按词库分节映射违规级别） */
    private ComplianceResult checkByDict(String text, TextType type) {
        List<SensitiveWordFilter.Hit> hits = sensitiveWordFilter.findHits(text);
        if (hits.isEmpty()) {
            return ComplianceResult.pass();
        }
        SensitiveWordFilter.Hit worst = hits.stream()
                .max(Comparator.comparingInt(h -> h.level().ordinal()))
                .orElseThrow();
        audit(type, "sensitive_word:" + worst.level(), worst.word(), text);
        return switch (worst.level()) {
            case SEVERE -> ComplianceResult.reject(ViolationLevel.SEVERE,
                    "high_risk", worst.word(), SEVERE_INTERVENTION);
            case MODERATE -> ComplianceResult.reject(ViolationLevel.MODERATE,
                    "sensitive_word", worst.word(), MODERATE_FALLBACK);
            default -> mildReject(worst.word());
        };
    }

    private ComplianceResult mildReject(String word) {
        ComplianceResult result = ComplianceResult.reject(ViolationLevel.MILD,
                "sensitive_word", word, null);
        result.setSuggestion("回复中不应出现「" + word + "」类表述，请调整为正向、非评判的表达后重生成");
        return result;
    }

    /** 二级校验：大模型语义合规（模型异常时降级放行——一级词库校验已通过） */
    private ComplianceResult checkByLlm(String text, TextType type) {
        LlmVerdict verdict;
        try {
            String prompt = promptUtil.render("compliance-check.txt",
                    Map.of("textType", type.name(), "content", text));
            verdict = llmGateway.callStructured(LlmRequest.of(AgentRole.COMPLIANCE, prompt), LlmVerdict.class);
        } catch (Exception e) {
            log.warn("合规模型校验失败，降级放行（一级词库校验已通过）: {}", e.getMessage());
            return ComplianceResult.pass();
        }
        if (verdict == null || verdict.passed()) {
            return ComplianceResult.pass();
        }
        audit(type, verdict.violationType(), verdict.suggestion(), text);
        return toResult(verdict);
    }

    private ComplianceResult toResult(LlmVerdict verdict) {
        if ("high_risk".equals(verdict.violationType())) {
            return ComplianceResult.reject(ViolationLevel.SEVERE,
                    verdict.violationType(), null, SEVERE_INTERVENTION);
        }
        if ("illegal".equals(verdict.violationType())) {
            return ComplianceResult.reject(ViolationLevel.MODERATE,
                    verdict.violationType(), null, MODERATE_FALLBACK);
        }
        // 诊断表述 / 负面引导：轻度违规，返回修改意见触发重生成
        ComplianceResult result = ComplianceResult.reject(ViolationLevel.MILD,
                verdict.violationType(), null, null);
        result.setSuggestion(verdict.suggestion());
        return result;
    }

    /** 所有拦截操作留痕（docs/agent_implementation.md §3.6） */
    private void audit(TextType type, String violationType, String detail, String text) {
        if (!complianceProperties.isRiskLogEnabled()) {
            return;
        }
        String excerpt = text.length() > 100 ? text.substring(0, 100) + "..." : text;
        RISK_LOG.warn("合规拦截 | type={} | violation={} | detail={} | excerpt={}",
                type, violationType, detail, excerpt);
    }

    /** 合规大模型结构化裁决 */
    public record LlmVerdict(boolean passed, String violationType, String suggestion) {
    }
}
