package com.mirror.agent.agent.resident;

import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.entity.SceneCategory;
import com.mirror.agent.llm.AgentRole;
import com.mirror.agent.llm.LlmGateway;
import com.mirror.agent.llm.LlmRequest;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.IncidentJudgeResult;
import com.mirror.agent.message.IncidentSummaryResult;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 事由分析 Agent：① 查重灰区（相似度落在上下阈值之间）的「是否同一事由」裁决；
 * ② 封稿时按事实列表归并生成现状摘要（事由链路 v2，取代逐轮重写）。
 * 调用失败（LlmGateway 已做重试）保守降级：裁决按「不同事由」，摘要返回空由调用方保留 bootstrap。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IncidentAgent {

    private static final String TEMPLATE = "incident-dedup-judge.txt";
    private static final String SUMMARY_TEMPLATE = "incident-summary.txt";

    private final LlmGateway llmGateway;
    private final PromptUtil promptUtil;

    /** 裁决两个事由描述是否同一件事 */
    public AgentResult<IncidentJudgeResult> judgeSameIncident(String existingDesc, String candidateDesc) {
        String prompt = promptUtil.render(TEMPLATE, Map.of(
                "existing", existingDesc, "candidate", candidateDesc));
        try {
            IncidentJudgeResult result = llmGateway.callStructured(
                    LlmRequest.of(AgentRole.INCIDENT, prompt), IncidentJudgeResult.class);
            if (result == null) {
                throw new IllegalStateException("模型返回空裁决结果");
            }
            return AgentResult.ok(result);
        } catch (Exception e) {
            log.warn("同事由裁决失败，保守判为不同事由: {}", e.getMessage());
            return AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "同事由裁决降级");
        }
    }

    /**
     * 封稿/刷新时依据事实列表归并生成现状摘要，并判定事由场景分类（19 类白名单，
     * 选项由 {@code SceneCategory.joinedLabels()} 注入 prompt，单一事实源）。
     *
     * <p>降级语义：摘要失败返回 data=null（调用方保留 bootstrap）；
     * scene 越界白名单归一为「其他」，缺失为 null（调用方保留原分类不覆盖）。</p>
     */
    public AgentResult<IncidentSummaryResult> summarizeIncident(String name, String factsText,
                                                          String coreFactsText, String preEmotionHint) {
        String prompt = promptUtil.render(SUMMARY_TEMPLATE, Map.of(
                "name", name == null ? "" : name,
                "facts", factsText == null ? "" : factsText,
                "coreFacts", coreFactsText == null || coreFactsText.isBlank() ? "（无）" : coreFactsText,
                "preEmotionHint", preEmotionHint == null || preEmotionHint.isBlank()
                        ? "（无）" : preEmotionHint,
                "scenes", SceneCategory.joinedLabels(),
                "sceneCount", String.valueOf(SceneCategory.values().length)));
        try {
            IncidentSummaryResult result = llmGateway.callStructured(
                    LlmRequest.of(AgentRole.INCIDENT, prompt), IncidentSummaryResult.class);
            if (result == null || result.getSummary() == null || result.getSummary().isBlank()) {
                throw new IllegalStateException("模型返回空摘要");
            }
            String rawScene = result.getScene();
            String scene = SceneCategory.normalizeLabel(rawScene);
            if (rawScene != null && !rawScene.isBlank() && scene != null && !scene.equals(rawScene.trim())) {
                log.warn("场景分类越界白名单，归一为「其他」: raw={}", rawScene);
            }
            result.setScene(scene);
            return AgentResult.ok(result);
        } catch (Exception e) {
            log.warn("现状摘要生成失败，保留 bootstrap 摘要: {}", e.getMessage());
            return AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "摘要生成降级");
        }
    }
}
