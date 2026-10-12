package com.mirror.agent.controller;

import com.mirror.agent.common.Result;
import com.mirror.agent.message.ChatRequest;
import com.mirror.agent.message.ChatResponse;
import com.mirror.agent.service.ChatPushService;
import com.mirror.agent.service.ChatService;
import com.mirror.agent.service.ConversationService;
import com.mirror.agent.util.UserContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

/**
 * 对话接口：同步 JSON 与 SSE 流式两种模式，均由 DP 调度 Agent 编排全链路。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatPushService chatPushService;
    private final ConversationService conversationService;

    @PostMapping
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return Result.ok(chatService.chat(UserContext.getUserId(), request));
    }


    /** SSE 流式对话（EventSource 仅支持 GET，参数走 Query） */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@RequestParam String content,
                               @RequestParam(required = false) Long conversationId) {
        ChatRequest request = new ChatRequest();
        request.setContent(content);
        request.setConversationId(conversationId);
        return chatService.stream(UserContext.getUserId(), request);
    }

    /**
     * 会话级 SSE 长连接订阅：接收归集待定态（aggregation_pending）
     * 与 Agent 主动消息（agent_message，超时追问等，均已先落库）。
     * EventSource 无法携带请求头，鉴权 token 走查询参数。
     */
    @GetMapping(value = "/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam Long conversationId) {
        conversationService.require(conversationId, UserContext.getUserId());
        return chatPushService.subscribe(conversationId);
    }
}
