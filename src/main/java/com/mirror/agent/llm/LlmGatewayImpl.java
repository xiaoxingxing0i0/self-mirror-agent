package com.mirror.agent.llm;

import com.mirror.agent.config.LlmProperties;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.concurrent.Callable;
import java.util.function.Function;

/**
 * LlmGateway 默认实现：
 * 1. 路由：providerOverride 优先，否则查 mirror.llm.routing.&lt;role&gt;.provider；
 * 2. 容错：同步调用套 resilience4j agentCall 实例（内层 Retry 3 次退避 → 外层 CircuitBreaker）；
 * 3. 降级：主通道最终失败且 routing.fallback != none 时，用降级 provider 的 client 重发一次
 *    （显式 override 不降级；降级通道不再套重试/熔断）；
 * 4. 流式：不挂容错与降级，错误直接上抛（SSE 端已有处理）。
 */
@Slf4j
@Service
public class LlmGatewayImpl implements LlmGateway {

    private static final String RESILIENCE_INSTANCE = "agentCall";

    private final ChatClientRegistry registry;
    private final LlmProperties properties;
    private final Retry retry;
    private final CircuitBreaker circuitBreaker;

    public LlmGatewayImpl(ChatClientRegistry registry, LlmProperties properties,
                          RetryRegistry retryRegistry, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.registry = registry;
        this.properties = properties;
        this.retry = retryRegistry.retry(RESILIENCE_INSTANCE);
        this.circuitBreaker = circuitBreakerRegistry.circuitBreaker(RESILIENCE_INSTANCE);
    }

    @Override
    public String call(LlmRequest request) {
        return execute(request, spec -> spec.call().content());
    }

    @Override
    public <T> T callStructured(LlmRequest request, Class<T> type) {
        return execute(request, spec -> spec.call().entity(type));
    }

    @Override
    public Flux<String> stream(LlmRequest request) {
        ChatClient client = registry.get(request.role(), resolveProvider(request));
        return requestSpec(client, request).stream().content();
    }

    /** 同步执行：Retry(内) + CircuitBreaker(外)，最终失败走降级 */
    private <T> T execute(LlmRequest request, Function<ChatClient.ChatClientRequestSpec, T> action) {
        Provider primary = resolveProvider(request);
        Callable<T> decorated = CircuitBreaker.decorateCallable(circuitBreaker,
                Retry.decorateCallable(retry,
                        () -> action.apply(requestSpec(registry.get(request.role(), primary), request))));
        try {
            return decorated.call();
        } catch (Exception e) {
            Provider fallback = fallbackProvider(request, primary);
            if (fallback == null) {
                throw e instanceof RuntimeException re ? re
                        : new IllegalStateException("LLM 调用失败: role=" + request.role(), e);
            }
            log.warn("LLM 主通道失败，降级 {} -> {}: role={}, err={}",
                    primary, fallback, request.role(), e.getMessage());
            return action.apply(requestSpec(registry.get(request.role(), fallback), request));
        }
    }

    private Provider resolveProvider(LlmRequest request) {
        if (request.providerOverride() != null) {
            return request.providerOverride();
        }
        return Provider.of(properties.routeOf(request.role().configKey()).getProvider());
    }

    /** 降级通道：override 场景不降级；fallback=none 或与主通道相同不降级 */
    private Provider fallbackProvider(LlmRequest request, Provider primary) {
        if (request.providerOverride() != null) {
            return null;
        }
        String fallback = properties.routeOf(request.role().configKey()).getFallback();
        if (Provider.isNone(fallback)) {
            return null;
        }
        Provider provider = Provider.of(fallback);
        return provider == primary ? null : provider;
    }

    private ChatClient.ChatClientRequestSpec requestSpec(ChatClient client, LlmRequest request) {
        ChatClient.ChatClientRequestSpec spec = client.prompt();
        if (request.extraSystem() != null && !request.extraSystem().isBlank()) {
            // 请求级 .system() 会整体覆盖 defaultSystem，故重组「角色 System Prompt + 追加指令」
            spec = spec.system(registry.systemText(request.role()) + "\n\n" + request.extraSystem());
        }
        return spec.user(request.userText());
    }
}
