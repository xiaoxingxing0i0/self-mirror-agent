package com.mirror.agent.agent.temp;

import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.llm.AgentRole;
import com.mirror.agent.llm.LlmGateway;
import com.mirror.agent.llm.LlmRequest;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 动态临时 Agent 工厂（docs/system_architecture.md：agent/temp 动态临时 Agent 实现）。
 * 基于 LlmGateway（TEMP_SUMMARIZE 角色，默认路由 ollama 本地模型）组装轻量专项 Agent，
 * 创建成本极低、无状态；Prompt 走 prompts/summarize.txt 模板。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DynamicAgentFactory {

    private static final String TEMPLATE = "summarize.txt";

    private final LlmGateway llmGateway;
    private final PromptUtil promptUtil;

    /**
     * 创建文本摘要临时 Agent。
     *
     * @param instruction 附加摘要要求（如「压缩为 100 字以内的早期对话摘要」）
     */
    public TempAgent createSummarizeAgent(String instruction) {
        return new SummarizeTempAgent(llmGateway, promptUtil, instruction);
    }

    /** 摘要临时 Agent 实现 */
    @RequiredArgsConstructor
    static class SummarizeTempAgent implements TempAgent {

        private final LlmGateway llmGateway;
        private final PromptUtil promptUtil;
        private final String instruction;

        @Override
        public String name() {
            return "temp-summarize";
        }

        @Override
        public AgentResult<String> execute(Map<String, Object> params) {
            String text = String.valueOf(params.getOrDefault("text", ""));
            if (text.isBlank()) {
                return AgentResult.fail(ErrorCode.PARAM_MISSING, "缺少待摘要文本");
            }
            try {
                String prompt = promptUtil.render(TEMPLATE, Map.of(
                        "instruction", instruction == null ? "" : instruction,
                        "text", text));
                String summary = llmGateway.call(LlmRequest.of(AgentRole.TEMP_SUMMARIZE, prompt));
                return AgentResult.ok(summary);
            } catch (Exception e) {
                log.error("临时摘要 Agent 执行失败", e);
                return AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "摘要生成失败: " + e.getMessage());
            }
        }
    }
}
