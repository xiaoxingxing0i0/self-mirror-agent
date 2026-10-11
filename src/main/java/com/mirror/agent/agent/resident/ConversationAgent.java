package com.mirror.agent.agent.resident;

import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.Map;

/**
 * 用户对话 Agent（docs/agent_definition.md §2.3）。
 * 4+1 类场景化 Prompt 模板；人设固化在 ChatClient 默认 System Prompt（AiConfig）；
 * 生成结果先返回调度 Agent，经合规校验后再输出给用户；支持 SSE 流式输出。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationAgent {

    /** 对话场景 → Prompt 模板 */
    public enum Scene {
        DAILY("chat-daily.txt"),
        COMFORT("chat-comfort.txt"),
        CLARIFY("chat-clarify.txt"),
        TASK_NOTIFY("task-notify.txt"),
        RESULT_NOTIFY("result-notify.txt");

        private final String template;

        Scene(String template) {
            this.template = template;
        }

        public String template() {
            return template;
        }
    }

    private final ChatClient chatClient;
    private final PromptUtil promptUtil;

    /** 同步生成回复 */
    public AgentResult<String> generateReply(Scene scene, Map<String, String> variables) {
        String prompt = promptUtil.render(scene.template(), variables);
        try {
            String reply = chatClient.prompt().user(prompt).call().content();
            if (reply == null || reply.isBlank()) {
                return AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "模型返回空内容");
            }
            return AgentResult.ok(reply.trim());
        } catch (Exception e) {
            log.error("对话生成失败: scene={}", scene, e);
            return AgentResult.fail(ErrorCode.AI_MODEL_ERROR, "对话生成失败: " + e.getMessage());
        }
    }

    /** 流式生成回复（SSE 逐字返回） */
    public Flux<String> streamReply(Scene scene, Map<String, String> variables) {
        String prompt = promptUtil.render(scene.template(), variables);
        return chatClient.prompt().user(prompt).stream().content();
    }
}
