package com.mirror.agent.agent.resident;

import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.LanguageAnalysisResult;
import com.mirror.agent.message.TagItem;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 语言分析 Agent（docs/agent_definition.md §2.2）。
 * JSON Schema 强制结构化输出；解析失败自动重试 1 次，仍失败则降级为「闲聊 + 待澄清」。
 * 置信度低于阈值的标签标记为待确认，不写入正式画像库。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LanguageAnalysisAgent {

    private static final String TEMPLATE = "language-analysis.txt";

    private final ChatClient chatClient;
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
        LanguageAnalysisResult result = callModel(prompt);
        if (result == null) {
            result = callModel(prompt);
        }
        if (result == null) {
            log.warn("语言分析结构化输出连续失败，降级为待澄清闲聊");
            return AgentResult.degraded(fallbackResult(), "语言分析降级");
        }
        postProcess(result);
        return AgentResult.ok(result);
    }

    private LanguageAnalysisResult callModel(String prompt) {
        try {
            return chatClient.prompt().user(prompt).call().entity(LanguageAnalysisResult.class);
        } catch (Exception e) {
            log.warn("语言分析模型调用/解析失败: {}", e.getMessage());
            return null;
        }
    }

    /** 阈值后处理：低置信度 → 待确认；缺省意图 → chat + 待澄清 */
    private void postProcess(LanguageAnalysisResult result) {
        double threshold = agentProperties.getLowConfidenceThreshold();
        for (TagItem tag : result.getTags()) {
            tag.setNeedConfirm(tag.getConfidence() < threshold);
        }
        if (result.getPrimaryIntent() == null || result.getPrimaryIntent().isBlank()) {
            result.setPrimaryIntent("chat");
            result.setNeedClarify(true);
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
