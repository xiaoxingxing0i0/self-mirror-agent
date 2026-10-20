package com.mirror.agent.llm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 模型出入参日志 Advisor（Spring AI 1.0 CallAdvisor/StreamAdvisor 切面）：
 * call/stream 前后打印完整请求文本（System/User）、回复文本、耗时与 token 用量，
 * 每个 角色×provider 一个实例（ChatClientConfig 装配），日志行可按 role/provider 归因。
 *
 * <p>纯观察切面：不修改请求与响应；日志构建异常只告警不阻断模型调用；
 * 开关与截断上限走 mirror.llm.io-log-enabled / io-log-max-chars。
 * 注意流式回复在 doOnComplete 时一次性打印聚合全文（不逐 chunk 刷屏）。</p>
 */
@Slf4j
public class ModelIoLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    private final String role;
    private final String provider;
    private final int maxChars;

    public ModelIoLoggingAdvisor(String role, String provider, int maxChars) {
        this.role = role;
        this.provider = provider;
        this.maxChars = maxChars;
    }

    @Override
    public String getName() {
        return "model-io-log";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        safeLog(() -> logRequest(request));
        long start = System.currentTimeMillis();
        try {
            ChatClientResponse response = chain.nextCall(request);
            safeLog(() -> logResponse(response, System.currentTimeMillis() - start));
            return response;
        } catch (RuntimeException e) {
            safeLog(() -> log.warn("[模型响应] role={} provider={} 耗时={}ms 调用失败: {}",
                    role, provider, System.currentTimeMillis() - start, e.getMessage()));
            throw e;
        }
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // defer：每次订阅独立构建聚合缓冲，避免异常场景下重订阅串内容
        return Flux.defer(() -> {
            safeLog(() -> logRequest(request));
            long start = System.currentTimeMillis();
            StringBuilder buffer = new StringBuilder();
            AtomicReference<Usage> usageRef = new AtomicReference<>();
            return chain.nextStream(request)
                    .doOnNext(chunk -> collectChunk(chunk, buffer, usageRef))
                    .doOnComplete(() -> safeLog(() ->
                            log.info("[模型响应] role={} provider={} 耗时={}ms tokens={}/{} 流式回复={}",
                                    role, provider, System.currentTimeMillis() - start,
                                    promptTokens(usageRef.get()), completionTokens(usageRef.get()),
                                    truncate(buffer.toString()))))
                    .doOnError(error -> safeLog(() ->
                            log.warn("[模型响应] role={} provider={} 耗时={}ms 流式失败: {} | 已累积={}",
                                    role, provider, System.currentTimeMillis() - start,
                                    error.getMessage(), truncate(buffer.toString()))));
        });
    }

    // ==================== 观察内部件 ====================

    private void collectChunk(ChatClientResponse chunk, StringBuilder buffer,
                              AtomicReference<Usage> usageRef) {
        try {
            ChatResponse chatResponse = chunk.chatResponse();
            if (chatResponse == null) {
                return;
            }
            if (chatResponse.getMetadata() != null && chatResponse.getMetadata().getUsage() != null) {
                usageRef.set(chatResponse.getMetadata().getUsage());
            }
            if (chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
                String text = chatResponse.getResult().getOutput().getText();
                if (text != null) {
                    buffer.append(text);
                }
            }
        } catch (Exception e) {
            log.debug("流式 chunk 文本聚合异常（忽略）: {}", e.getMessage());
        }
    }

    private void logRequest(ChatClientRequest request) {
        StringBuilder systemText = new StringBuilder();
        StringBuilder userText = new StringBuilder();
        if (request.prompt() != null) {
            for (Message message : request.prompt().getInstructions()) {
                String text = message.getText();
                if (text == null) {
                    continue;
                }
                if (message.getMessageType() == MessageType.SYSTEM) {
                    systemText.append(text);
                } else if (message.getMessageType() == MessageType.USER) {
                    userText.append(text);
                }
            }
        }
        log.info("[模型请求] role={} provider={} system({}字符)={}",
                role, provider, systemText.length(), truncate(systemText.toString()));
        log.info("[模型请求] role={} provider={} user({}字符)={}",
                role, provider, userText.length(), truncate(userText.toString()));
    }

    private void logResponse(ChatClientResponse response, long elapsedMs) {
        ChatResponse chatResponse = response == null ? null : response.chatResponse();
        String text = null;
        Usage usage = null;
        if (chatResponse != null) {
            if (chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
                text = chatResponse.getResult().getOutput().getText();
            }
            if (chatResponse.getMetadata() != null) {
                usage = chatResponse.getMetadata().getUsage();
            }
        }
        log.info("[模型响应] role={} provider={} 耗时={}ms tokens={}/{} 回复={}",
                role, provider, elapsedMs, promptTokens(usage), completionTokens(usage), truncate(text));
    }

    private String promptTokens(Usage usage) {
        return usage == null ? "-" : String.valueOf(usage.getPromptTokens());
    }

    private String completionTokens(Usage usage) {
        return usage == null ? "-" : String.valueOf(usage.getCompletionTokens());
    }

    private String truncate(String text) {
        if (text == null || text.isEmpty()) {
            return "(空)";
        }
        if (maxChars <= 0 || text.length() <= maxChars) {
            return text;
        }
        return text.substring(0, maxChars) + "…(截断 " + (text.length() - maxChars) + " 字符)";
    }

    /** 日志自身不打断主链路：任何异常吞掉并告警 */
    private void safeLog(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("模型出入参日志异常（忽略）: role={}, provider={}, err={}",
                    role, provider, e.getMessage());
        }
    }
}
