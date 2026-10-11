package com.mirror.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.entity.Conversation;
import com.mirror.agent.entity.Message;
import com.mirror.agent.repository.ConversationMapper;
import com.mirror.agent.repository.MessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话与消息持久化服务（对话写入链路，docs/data_model.md §4.4-1）。
 */
@Service
@RequiredArgsConstructor
public class ConversationService {

    private static final int TITLE_MAX_LEN = 20;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    /** 会话 ID 为空时创建新会话，否则校验归属后复用 */
    public Conversation getOrCreate(Long userId, Long conversationId) {
        if (conversationId != null) {
            return require(conversationId, userId);
        }
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setStartTime(LocalDateTime.now());
        conversation.setLastActiveTime(LocalDateTime.now());
        conversation.setStatus(0);
        conversation.setMessageCount(0);
        conversationMapper.insert(conversation);
        return conversation;
    }

    public Conversation require(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectOne(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getId, conversationId)
                .eq(Conversation::getUserId, userId));
        if (conversation == null) {
            throw new BizException(ErrorCode.CONVERSATION_NOT_FOUND);
        }
        return conversation;
    }

    /** 保存消息并联动更新会话活跃时间 / 消息数 / 自动标题 */
    @Transactional(rollbackFor = Exception.class)
    public Message saveMessage(Long conversationId, Long userId, int role, String content) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setCreateTime(LocalDateTime.now());
        messageMapper.insert(message);
        touchConversation(conversationId, role, content);
        return message;
    }

    private void touchConversation(Long conversationId, int role, String content) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        conversation.setLastActiveTime(LocalDateTime.now());
        conversation.setMessageCount(conversation.getMessageCount() + 1);
        if (conversation.getTitle() == null && role == Message.ROLE_USER) {
            conversation.setTitle(content.length() > TITLE_MAX_LEN
                    ? content.substring(0, TITLE_MAX_LEN) : content);
        }
        conversationMapper.updateById(conversation);
    }

    /** 最近 N 条消息（按时间正序返回） */
    public List<Message> recentMessages(Long conversationId, int limit) {
        List<Message> messages = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getCreateTime)
                .last("LIMIT " + limit));
        List<Message> ordered = new ArrayList<>(messages);
        Collections.reverse(ordered);
        return ordered;
    }

    public List<Conversation> listByUser(Long userId) {
        return conversationMapper.selectList(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getLastActiveTime));
    }

    public List<Message> listMessages(Long userId, Long conversationId) {
        require(conversationId, userId);
        return messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getCreateTime));
    }

    public void close(Long userId, Long conversationId) {
        Conversation conversation = require(conversationId, userId);
        conversation.setStatus(1);
        conversationMapper.updateById(conversation);
    }
}
