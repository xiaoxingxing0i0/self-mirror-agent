package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.Event;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事件表 Mapper。
 */
@Mapper
public interface EventMapper extends BaseMapper<Event> {
}
