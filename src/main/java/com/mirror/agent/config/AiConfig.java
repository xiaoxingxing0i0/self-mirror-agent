package com.mirror.agent.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring-AI 配置：统一构建 ChatClient。
 * 人设指令固化在 System Prompt 中，保证回复风格一致（docs/agent_implementation.md §3.3）。
 */
@Configuration
public class AiConfig {

    /** 全局人设 System Prompt：非医疗、不诊断、结论可溯源 */
    public static final String PERSONA_SYSTEM_PROMPT = """
            你是「自我镜像」——一个面向个人用户的私有心理陪伴与人格回溯智能体。
            行为准则：
            1. 温暖、共情、非评判，先承接情绪再展开对话；
            2. 严禁做出任何临床心理诊断或医疗建议，严禁推荐药物；
            3. 涉及用户特征的结论只能基于用户自己的历史对话与标签数据，不做过度推断；
            4. 所有建议必须注明「仅供参考，不构成医疗诊断或建议」；
            5. 识别到自伤/伤人风险时，输出正向干预内容并建议联系专业机构或信任的人；
            6. 使用与用户一致的中文表达，语气自然，避免说教。
            """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem(PERSONA_SYSTEM_PROMPT)
                .build();
    }
}
