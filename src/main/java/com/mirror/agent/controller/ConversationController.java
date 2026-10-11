package com.mirror.agent.controller;

import com.mirror.agent.common.Result;
import com.mirror.agent.entity.Conversation;
import com.mirror.agent.entity.Message;
import com.mirror.agent.service.ConversationService;
import com.mirror.agent.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话接口：会话列表 / 消息历史（人格回溯查询入口）/ 关闭会话。
 */
@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationService conversationService;

    @GetMapping
    public Result<List<Conversation>> list() {
        return Result.ok(conversationService.listByUser(UserContext.getUserId()));
    }

    @GetMapping("/{conversationId}/messages")
    public Result<List<Message>> messages(@PathVariable Long conversationId) {
        return Result.ok(conversationService.listMessages(UserContext.getUserId(), conversationId));
    }

    @PostMapping("/{conversationId}/close")
    public Result<Void> close(@PathVariable Long conversationId) {
        conversationService.close(UserContext.getUserId(), conversationId);
        return Result.ok();
    }
}
