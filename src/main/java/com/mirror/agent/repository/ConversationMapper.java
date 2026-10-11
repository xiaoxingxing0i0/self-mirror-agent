package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.Conversation;
import org.apache.ibatis.annotations.Mapper;

/**
 * Conversation Mapper。
 */
@Mapper
public interface ConversationMapper extends BaseMapper<Conversation> {
}
