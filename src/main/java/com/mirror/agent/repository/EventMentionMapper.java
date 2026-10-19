package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.EventMention;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事件提及明细 Mapper。
 */
@Mapper
public interface EventMentionMapper extends BaseMapper<EventMention> {
}
