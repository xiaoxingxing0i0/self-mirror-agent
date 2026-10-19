package com.mirror.agent.llm;

import com.mirror.agent.config.LlmProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LlmGatewayImpl 单测（纯 Mockito，不加载 Spring 上下文，与项目现有测试风格一致）：
 * 路由、降级、no-fallback、显式 override、结构化输出、extraSystem 重组、流式。
 */
class LlmGatewayImplTest {

    private ChatClient qwenClient;
    private ChatClient ollamaClient;
    private ChatClient.ChatClientRequestSpec qwenSpec;
    private ChatClient.ChatClientRequestSpec ollamaSpec;
    private ChatClient.CallResponseSpec qwenCallSpec;
    private ChatClient.CallResponseSpec ollamaCallSpec;
    private LlmGatewayImpl gateway;

    /** 测试用裁决结果载体 */
    record Verdict(boolean same) {
    }

    @BeforeEach
    void setUp() {
        qwenClient = mock(ChatClient.class);
        ollamaClient = mock(ChatClient.class);
        qwenSpec = stubSpec(qwenClient);
        ollamaSpec = stubSpec(ollamaClient);
        qwenCallSpec = qwenSpec.call();
        ollamaCallSpec = ollamaSpec.call();

        ChatClientRegistry registry = new ChatClientRegistry();
        for (AgentRole role : AgentRole.values()) {
            registry.register(role, Provider.QWEN, qwenClient, "qwen-system");
            registry.register(role, Provider.OLLAMA, ollamaClient, "ollama-system");
        }

        gateway = new LlmGatewayImpl(registry, testProperties(),
                RetryRegistry.of(RetryConfig.custom()          // 快速重试，避免单测等待退避
                        .maxAttempts(2)
                        .waitDuration(Duration.ofMillis(1))
                        .build()),
                CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                        .slidingWindowSize(100)
                        .failureRateThreshold(50)
                        .build()));
    }

    @Test
    @DisplayName("按路由表选择主 provider：temp-summarize 走 ollama")
    void routesByConfiguredProvider() {
        when(ollamaCallSpec.content()).thenReturn("摘要结果");

        String result = gateway.call(LlmRequest.of(AgentRole.TEMP_SUMMARIZE, "长文本"));

        assertThat(result).isEqualTo("摘要结果");
        verify(qwenClient, never()).prompt();
    }

    @Test
    @DisplayName("主通道失败且 fallback!=none：conversation 降级到 ollama")
    void fallsBackWhenPrimaryFails() {
        when(qwenCallSpec.content()).thenThrow(new RuntimeException("qwen down"));
        when(ollamaCallSpec.content()).thenReturn("本地回复");

        String result = gateway.call(LlmRequest.of(AgentRole.CONVERSATION, "你好"));

        assertThat(result).isEqualTo("本地回复");
    }

    @Test
    @DisplayName("fallback=none：compliance 主通道失败直接上抛，不降级")
    void noFallbackWhenConfiguredNone() {
        when(qwenCallSpec.content()).thenThrow(new RuntimeException("qwen down"));

        assertThatThrownBy(() -> gateway.call(LlmRequest.of(AgentRole.COMPLIANCE, "文本")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("qwen down");
        verify(ollamaClient, never()).prompt();
    }

    @Test
    @DisplayName("显式 override：跳过路由表主通道")
    void overrideSelectsProvider() {
        when(qwenCallSpec.content()).thenReturn("云端回复");

        String result = gateway.call(
                LlmRequest.of(AgentRole.TEMP_SUMMARIZE, "长文本", Provider.QWEN));

        assertThat(result).isEqualTo("云端回复");
        verify(ollamaClient, never()).prompt();
    }

    @Test
    @DisplayName("显式 override 失败：不降级，异常上抛")
    void overrideFailureDoesNotFallback() {
        when(ollamaCallSpec.content()).thenThrow(new RuntimeException("ollama down"));

        assertThatThrownBy(() -> gateway.call(
                LlmRequest.of(AgentRole.CONVERSATION, "你好", Provider.OLLAMA)))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ollama down");
        verify(qwenClient, never()).prompt();
    }

    @Test
    @DisplayName("结构化输出：entity 结果透传")
    void structuredCallReturnsEntity() {
        when(qwenCallSpec.entity(eq(Verdict.class))).thenReturn(new Verdict(true));

        Verdict verdict = gateway.callStructured(
                LlmRequest.of(AgentRole.EVENT, "事件A/B"), Verdict.class);

        assertThat(verdict.same()).isTrue();
    }

    @Test
    @DisplayName("extraSystem：请求级 system 重组「角色提示词 + 追加指令」")
    void extraSystemRecomposedWithRolePrompt() {
        when(ollamaCallSpec.content()).thenReturn("ok");

        gateway.call(LlmRequest.withSystem(AgentRole.TEMP_SUMMARIZE, "文本", "压缩为50字"));

        verify(ollamaSpec).system("ollama-system\n\n压缩为50字");
    }

    @Test
    @DisplayName("流式：直接返回 Flux，不参与降级")
    void streamReturnsFlux() {
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        when(ollamaSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("你", "好"));

        List<String> chunks = gateway.stream(
                LlmRequest.of(AgentRole.TEMP_SUMMARIZE, "文本")).collectList().block();

        assertThat(chunks).containsExactly("你", "好");
        verify(qwenClient, never()).prompt();
    }

    // ---------- helpers ----------

    private static LlmProperties testProperties() {
        LlmProperties properties = new LlmProperties();
        Map<String, LlmProperties.Route> routing = new LinkedHashMap<>();
        routing.put("conversation", route("qwen", "ollama"));
        routing.put("compliance", route("qwen", "none"));
        routing.put("event", route("qwen", "none"));
        routing.put("language-analysis", route("qwen", "none"));
        routing.put("profile-report", route("qwen", "ollama"));
        routing.put("temp-summarize", route("ollama", "qwen"));
        properties.setRouting(routing);
        return properties;
    }

    private static LlmProperties.Route route(String provider, String fallback) {
        LlmProperties.Route route = new LlmProperties.Route();
        route.setProvider(provider);
        route.setFallback(fallback);
        return route;
    }

    /** mock 出 fluent 调用链：prompt() → spec（user/system 返回自身）→ call/stream spec */
    private static ChatClient.ChatClientRequestSpec stubSpec(ChatClient client) {
        ChatClient.ChatClientRequestSpec spec = mock(ChatClient.ChatClientRequestSpec.class);
        when(client.prompt()).thenReturn(spec);
        when(spec.user(anyString())).thenReturn(spec);
        when(spec.system(anyString())).thenReturn(spec);
        when(spec.call()).thenReturn(mock(ChatClient.CallResponseSpec.class));
        when(spec.stream()).thenReturn(mock(ChatClient.StreamResponseSpec.class));
        return spec;
    }
}
