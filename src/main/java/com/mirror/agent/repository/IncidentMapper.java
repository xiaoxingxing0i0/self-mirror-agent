package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.Incident;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事由表 Mapper。
 */
@Mapper
public interface IncidentMapper extends BaseMapper<Incident> {
}
