package com.mirror.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * LLM 双后端与路由配置（mirror.llm.*）。
 * qwen / ollama 两个连接由 ChatModelConfig 手动装配为 ChatModel bean；
 * routing 表决定每个 AgentRole 的主 provider 与降级 provider。
 */
@Data
@ConfigurationProperties(prefix = "mirror.llm")
public class LlmProperties {

    /** 通义千问（DashScope OpenAI 兼容模式）连接参数 */
    private ProviderConn qwen = new ProviderConn();

    /** Ollama 本地连接参数 */
    private ProviderConn ollama = new ProviderConn();

    /** 角色路由表：key = AgentRole.configKey()（如 conversation / temp-summarize） */
    private Map<String, Route> routing = new LinkedHashMap<>();

    /** 模型出入参日志开关（ModelIoLoggingAdvisor：请求文本/回复文本/耗时/token 用量） */
    private boolean ioLogEnabled = true;

    /** 出入参日志单条文本截断上限（字符），≤0 = 不截断 */
    private int ioLogMaxChars = 4000;

    /** 按角色配置键取路由，缺失 fail-fast（启动期即暴露配置遗漏） */
    public Route routeOf(String configKey) {
        Route route = routing.get(configKey);
        if (route == null) {
            throw new IllegalStateException("mirror.llm.routing 缺少角色路由配置: " + configKey);
        }
        return route;
    }

    /** 按 provider 键取连接参数 */
    public ProviderConn connOf(String providerKey) {
        return switch (providerKey == null ? "" : providerKey.toLowerCase()) {
            case "qwen" -> qwen;
            case "ollama" -> ollama;
            default -> throw new IllegalArgumentException("未知的 LLM provider: " + providerKey);
        };
    }

    @Data
    public static class ProviderConn {
        private String apiKey;
        private String baseUrl;
        private String model;
        private Double temperature = 0.7;
    }

    @Data
    public static class Route {
        /** 主通道：qwen / ollama */
        private String provider;
        /** 降级通道：qwen / ollama / none（默认不降级） */
        private String fallback = "none";
    }
}
