package com.mirror.agent.service;

import com.mirror.agent.agent.resident.ConversationAgent;
import com.mirror.agent.dp.scheduler.DpScheduleAgent;
import com.mirror.agent.dp.scheduler.DpScheduleAgent.StreamPreparation;
import com.mirror.agent.message.ChatRequest;
import com.mirror.agent.message.ChatResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

/**
 * 对话应用服务：接口层唯一入口，委托 DP 调度 Agent 编排全链路。
 * 同步链路返回完整响应；SSE 链路组合「前置准备 → 流式生成 → 收尾合规归档」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private static final String STREAM_ERROR_HINT = "（生成中断：服务暂时不可用，请稍后重试）";

    private final DpScheduleAgent dpScheduleAgent;
    private final ConversationAgent conversationAgent;

    public ChatResponse chat(Long userId, ChatRequest request) {
        return dpScheduleAgent.handleChat(userId, request);
    }

    /** SSE 流式对话：逐字返回；全文生成完毕后统一做输出合规与归档 */
    public Flux<String> stream(Long userId, ChatRequest request) {
        StreamPreparation preparation = dpScheduleAgent.prepareStream(userId, request);
        if (preparation.intercepted()) {
            return Flux.just(preparation.interceptedReply());
        }
        StringBuilder fullReply = new StringBuilder();
        Flux<String> generation = conversationAgent
                .streamReply(preparation.scene(), preparation.vars())
                .doOnNext(fullReply::append)
                .onErrorResume(e -> {
                    log.error("流式生成异常: conversationId={}",
                            preparation.conversation().getId(), e);
                    return Flux.just(STREAM_ERROR_HINT);
                });
        return generation.concatWith(Flux.defer(() -> {
            dpScheduleAgent.finalizeStream(preparation, fullReply.toString());
            return Flux.empty();
        }));
    }
}
