package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.IncidentRelation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事由引用关系边 Mapper。
 */
@Mapper
public interface IncidentRelationMapper extends BaseMapper<IncidentRelation> {
}
