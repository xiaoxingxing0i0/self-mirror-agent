package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.Task;
import org.apache.ibatis.annotations.Mapper;

/**
 * Task Mapper。
 */
@Mapper
public interface TaskMapper extends BaseMapper<Task> {
}
