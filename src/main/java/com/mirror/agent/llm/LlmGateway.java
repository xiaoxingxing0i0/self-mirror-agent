package com.mirror.agent.llm;

import reactor.core.publisher.Flux;

/**
 * LLM 调用唯一入口：所有 Agent 通过本网关访问大模型，禁止直接注入 ChatClient。
 * 网关统一负责：按角色路由 provider、resilience4j 重试/熔断、失败降级（fallback）、日志。
 */
public interface LlmGateway {

    /**
     * 同步调用，返回文本内容。
     * 失败时按 mirror.llm.routing 配置的 fallback 降级重试一次（降级通道不再套重试/熔断）。
     */
    String call(LlmRequest request);

    /**
     * 流式调用（SSE 场景）。流式不做降级——首包失败直接上抛，由调用端处理。
     */
    Flux<String> stream(LlmRequest request);

    /**
     * 同步调用 + 结构化输出（Spring AI entity 转换，内部走 BeanOutputConverter）。
     * 解析失败视为调用失败，参与重试；安全关键角色（fallback=none）最终失败时上抛。
     */
    <T> T callStructured(LlmRequest request, Class<T> type);
}
