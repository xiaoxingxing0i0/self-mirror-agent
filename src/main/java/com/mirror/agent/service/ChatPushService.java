package com.mirror.agent.service;

import com.mirror.agent.message.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 会话级 SSE 长连接推送通道（GET /api/chat/subscribe）。
 * 投递原则：先落库、后投递——所有主动消息（归集待定态、超时追问等）先写 message 表，
 * 再尽力经本通道推送；连接断开时推送自然丢失，用户重开会话经历史/增量拉取兜底。
 * 已知限制：连接注册表在本机内存，多实例部署时仅同实例连接可收到推送（二期经 Redis Pub/Sub 打通）。
 */
@Slf4j
@Service
public class ChatPushService {

    /** 订阅长连接空闲超时：30 分钟，配合心跳由前端 EventSource 自动重连 */
    private static final long SUBSCRIBE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> channels = new ConcurrentHashMap<>();

    /** 建立会话级订阅通道（鉴权由拦截器完成；EventSource 无法带请求头，token 走查询参数） */
    public SseEmitter subscribe(Long conversationId) {
        SseEmitter emitter = new SseEmitter(SUBSCRIBE_TIMEOUT_MILLIS);
        channels.computeIfAbsent(conversationId, id -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(conversationId, emitter));
        emitter.onTimeout(() -> {
            remove(conversationId, emitter);
            emitter.complete();
        });
        emitter.onError(e -> remove(conversationId, emitter));
        sendQuietly(conversationId, emitter, "connected",
                Map.of("conversationId", conversationId));
        return emitter;
    }

    /** 推送归集待定态：Agent 正在等用户把话说完 */
    public void publishPending(Long conversationId, long deadlineAt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("deadlineAt", deadlineAt);
        publish(conversationId, "aggregation_pending", payload);
    }

    /** 推送已落库的 Agent 主动消息（超时追问/冲刷回复等） */
    public void publishAgentMessage(Long conversationId, ChatResponse response) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("messageId", response.getReplyMessageId());
        payload.put("content", response.getReply());
        payload.put("replyType", response.getReplyType());
        payload.put("emotionTone", response.getEmotionTone());
        payload.put("needClarify", response.isNeedClarify());
        payload.put("sendTime", LocalDateTime.now());
        publish(conversationId, "agent_message", payload);
    }

    /** 心跳：维持代理/网关连接，并清理已断开的死连接 */
    @Scheduled(fixedDelay = 20000)
    public void heartbeat() {
        channels.forEach((conversationId, emitters) -> emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                remove(conversationId, emitter);
            }
        }));
    }

    private void publish(Long conversationId, String event, Object payload) {
        List<SseEmitter> emitters = channels.get(conversationId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            sendQuietly(conversationId, emitter, event, payload);
        }
    }

    private void sendQuietly(Long conversationId, SseEmitter emitter, String event, Object payload) {
        try {
            emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
        } catch (Exception e) {
            log.debug("SSE 推送失败（连接已断开，消息已落库可重开拉取）: conversationId={}, event={}",
                    conversationId, event);
            remove(conversationId, emitter);
        }
    }

    private void remove(Long conversationId, SseEmitter emitter) {
        CopyOnWriteArrayList<SseEmitter> emitters = channels.get(conversationId);
        if (emitters != null) {
            emitters.remove(emitter);
        }
    }
}
