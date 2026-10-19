package com.mirror.agent.llm;

/**
 * LLM 服务提供方：qwen 云端（DashScope OpenAI 兼容模式）/ ollama 本地。
 * 与 application.yml 中 mirror.llm.routing.*.provider / fallback 的小写值对应。
 */
public enum Provider {

    /** 通义千问（云端） */
    QWEN("qwen"),

    /** Ollama（本地） */
    OLLAMA("ollama");

    private final String key;

    Provider(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /** 按配置键解析（大小写不敏感），非法值 fail-fast */
    public static Provider of(String key) {
        for (Provider provider : values()) {
            if (provider.key.equalsIgnoreCase(key)) {
                return provider;
            }
        }
        throw new IllegalArgumentException("未知的 LLM provider: " + key);
    }

    /** 判断配置值是否为「不降级」（null / 空 / none） */
    public static boolean isNone(String key) {
        return key == null || key.isBlank() || "none".equalsIgnoreCase(key);
    }
}
