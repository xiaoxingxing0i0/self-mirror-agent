package com.mirror.agent.config;

import com.mirror.agent.llm.AgentRole;
import com.mirror.agent.llm.ChatClientRegistry;
import com.mirror.agent.llm.Provider;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * ChatClient 身份层：每个 AgentRole 一个独立 ChatClient（× 双 provider，共 12 个实例）。
 * System Prompt 全部来自 classpath 资源文件（代码零 prompt 文本）：
 * prompts/system/persona.txt（全局人设） + prompts/system/&lt;role&gt;.txt（角色定义），
 * 组合后经 {@link ChatClient.Builder#defaultSystem(Resource)} 以 Resource 方式设置。
 */
@Configuration
public class ChatClientConfig {

    private static final String SYSTEM_PROMPT_DIR = "prompts/system/";
    private static final String PERSONA_FILE = "persona.txt";

    @Bean
    public ChatClientRegistry chatClientRegistry(LlmProperties properties,
                                                 @Qualifier("qwenChatModel") ChatModel qwenChatModel,
                                                 @Qualifier("ollamaChatModel") ChatModel ollamaChatModel) {
        Map<Provider, ChatModel> models = Map.of(
                Provider.QWEN, qwenChatModel,
                Provider.OLLAMA, ollamaChatModel);
        String persona = loadSystemPrompt(PERSONA_FILE);
        ChatClientRegistry registry = new ChatClientRegistry();
        for (AgentRole role : AgentRole.values()) {
            // fail-fast：路由表必须覆盖所有角色，配置遗漏在启动期暴露
            properties.routeOf(role.configKey());
            String systemText = persona + "\n\n" + loadSystemPrompt(role.systemPromptFile());
            Resource systemResource = new ByteArrayResource(systemText.getBytes(StandardCharsets.UTF_8));
            for (Provider provider : Provider.values()) {
                ChatClient client = ChatClient.builder(models.get(provider))
                        .defaultSystem(systemResource)
                        .build();
                registry.register(role, provider, client, systemText);
            }
        }
        return registry;
    }

    private String loadSystemPrompt(String fileName) {
        try {
            return new ClassPathResource(SYSTEM_PROMPT_DIR + fileName)
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("System Prompt 资源加载失败: " + SYSTEM_PROMPT_DIR + fileName, e);
        }
    }
}
