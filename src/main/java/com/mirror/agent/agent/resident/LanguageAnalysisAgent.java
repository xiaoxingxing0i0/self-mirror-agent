package com.mirror.agent.agent.resident;

import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.llm.AgentRole;
import com.mirror.agent.llm.LlmGateway;
import com.mirror.agent.llm.LlmRequest;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.LanguageAnalysisResult;
import com.mirror.agent.message.TagItem;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 语言分析 Agent（docs/agent_definition.md §2.2）。
 * JSON Schema 强制结构化输出；重试由 LlmGateway（resilience4j）统一负责，
 * 最终失败则降级为「闲聊 + 待澄清」。
 * 置信度低于阈值的标签标记为待确认，不写入正式画像库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LanguageAnalysisAgent {

    private static final String TEMPLATE = "language-analysis.txt";

    /** 意图 code → 展示名（与提示词标签字典/示例一致，intent 对齐补全用） */
    private static final Map<String, String> INTENT_NAMES = Map.of(
            "chat", "日常闲聊", "consult", "咨询", "query", "查历史", "task", "任务");

    private final LlmGateway llmGateway;
    private final PromptUtil promptUtil;
    private final AgentProperties agentProperties;

    public AgentResult<LanguageAnalysisResult> analyze(String input, String history) {
        return analyze(input, history, null);
    }

    /**
     * 语言分析 + 事件抽取（单次结构化输出）。
     *
     * @param eventRefs 最近提及事件注入（事件同一性指认依据，场景 2 的数据来源；可空）
     */
    public AgentResult<LanguageAnalysisResult> analyze(String input, String history, String eventRefs) {
        String prompt = promptUtil.render(TEMPLATE, Map.of(
                "history", history == null || history.isBlank() ? "（无历史对话）" : history,
                "recentEvents", eventRefs == null || eventRefs.isBlank() ? "（无最近提及事件）" : eventRefs,
                "input", input));
        LanguageAnalysisResult result;
        try {
            result = llmGateway.callStructured(
                    LlmRequest.of(AgentRole.LANGUAGE_ANALYSIS, prompt), LanguageAnalysisResult.class);
        } catch (Exception e) {
            log.warn("语言分析模型调用/解析失败: {}", e.getMessage());
            result = null;
        }
        if (result == null) {
            log.warn("语言分析结构化输出失败，降级为待澄清闲聊");
            return AgentResult.degraded(fallbackResult(), "语言分析降级");
        }
        postProcess(result);
        return AgentResult.ok(result);
    }

    /** 阈值后处理：低置信度 → 待确认；intent 与 primaryIntent 一致性对齐 */
    private void postProcess(LanguageAnalysisResult result) {
        double threshold = agentProperties.getLowConfidenceThreshold();
        for (TagItem tag : result.getTags()) {
            tag.setNeedConfirm(tag.getConfidence() < threshold);
        }
        alignIntent(result);
    }

    /**
     * 意图一致性（提示词规则 1「intent 恰一个且与 primaryIntent 一致」的后端兜底）：
     * 路由字段优先——primaryIntent 非法/缺失时取 tags 中合法 intent 补，两边都不可用
     * 回落 chat + 待澄清；tags 侧收敛为恰一条并与 primaryIntent 同值，
     * 防止画像统计（tag_record.intent）与路由（IntentRouter 按 primaryIntent 分支）各信一边。
     */
    private void alignIntent(LanguageAnalysisResult result) {
        List<TagItem> intentTags = result.tagsOf(TagItem.CATEGORY_INTENT);
        String primary = result.getPrimaryIntent();
        boolean primaryValid = primary != null && INTENT_NAMES.containsKey(primary);
        String tagCode = intentTags.isEmpty() ? null : intentTags.get(0).getCode();
        boolean tagValid = tagCode != null && INTENT_NAMES.containsKey(tagCode);
        if (!primaryValid) {
            primary = tagValid ? tagCode : "chat";
            if (!tagValid) {
                result.setNeedClarify(true);
            }
        }
        result.setPrimaryIntent(primary);

        for (int i = intentTags.size() - 1; i >= 1; i--) {
            result.getTags().remove(intentTags.get(i));
        }
        if (intentTags.isEmpty()) {
            result.getTags().add(new TagItem(TagItem.CATEGORY_INTENT, primary,
                    INTENT_NAMES.get(primary), 0.6, true));
        } else {
            TagItem kept = intentTags.get(0);
            if (!primary.equals(kept.getCode())) {
                kept.setCode(primary);
                kept.setName(INTENT_NAMES.get(primary));
            }
        }
    }

    private LanguageAnalysisResult fallbackResult() {
        LanguageAnalysisResult result = new LanguageAnalysisResult();
        result.setPrimaryIntent("chat");
        result.setNeedClarify(true);
        result.setSummary("语义分析降级，待用户澄清");
        result.getTags().add(new TagItem(TagItem.CATEGORY_INTENT, "chat", "日常闲聊", 0.3, true));
        return result;
    }

    /** 历史对话批量打标（回溯处理入口，逐条复用 analyze） */
    public AgentResult<LanguageAnalysisResult> analyzeBatchItem(String content) {
        return analyze(content, null);
    }
}
