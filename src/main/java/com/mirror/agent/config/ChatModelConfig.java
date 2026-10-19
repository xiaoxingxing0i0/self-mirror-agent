package com.mirror.agent.config;

import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chat 模型连接层：手动装配双后端 ChatModel bean。
 * 因 openai / ollama 两个 starter 共存，application.yml 已设 spring.ai.model.chat=none
 * 关闭 chat 自动装配（embedding 仍走 openai 自动装配，固定 DashScope，与 Milvus 1024 维强耦合）。
 */
@Configuration
public class ChatModelConfig {

    /** 云端：通义千问（DashScope OpenAI 兼容模式） */
    @Bean("qwenChatModel")
    public OpenAiChatModel qwenChatModel(LlmProperties properties) {
        LlmProperties.ProviderConn qwen = properties.getQwen();
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(qwen.getBaseUrl())
                .apiKey(qwen.getApiKey())
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(qwen.getModel())
                        .temperature(qwen.getTemperature())
                        .build())
                .build();
    }

    /** 本地：Ollama 原生 API */
    @Bean("ollamaChatModel")
    public OllamaChatModel ollamaChatModel(LlmProperties properties) {
        LlmProperties.ProviderConn ollama = properties.getOllama();
        OllamaApi api = OllamaApi.builder()
                .baseUrl(ollama.getBaseUrl())
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .defaultOptions(OllamaOptions.builder()
                        .model(ollama.getModel())
                        .temperature(ollama.getTemperature())
                        .build())
                .build();
    }
}
