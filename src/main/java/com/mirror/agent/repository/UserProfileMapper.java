package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.UserProfile;
import org.apache.ibatis.annotations.Mapper;

/**
 * UserProfile Mapper。
 */
@Mapper
public interface UserProfileMapper extends BaseMapper<UserProfile> {
}
