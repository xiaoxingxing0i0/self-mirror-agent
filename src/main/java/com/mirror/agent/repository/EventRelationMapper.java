package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.EventRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事件引用关系边 Mapper。
 */
@Mapper
public interface EventRelationMapper extends BaseMapper<EventRelation> {
}
