package com.mirror.agent.controller;

import com.mirror.agent.common.Result;
import com.mirror.agent.message.ChatRequest;
import com.mirror.agent.message.ChatResponse;
import com.mirror.agent.service.ChatService;
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
import reactor.core.publisher.Flux;

/**
 * 对话接口：同步 JSON 与 SSE 流式两种模式，均由 DP 调度 Agent 编排全链路。
 */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;

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
}
