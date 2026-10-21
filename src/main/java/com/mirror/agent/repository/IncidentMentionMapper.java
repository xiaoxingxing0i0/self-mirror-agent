package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.IncidentMention;
import org.apache.ibatis.annotations.Mapper;

/**
 * 事由提及明细 Mapper。
 */
@Mapper
public interface IncidentMentionMapper extends BaseMapper<IncidentMention> {
}
