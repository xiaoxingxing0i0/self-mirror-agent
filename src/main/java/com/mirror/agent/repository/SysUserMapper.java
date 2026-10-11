package com.mirror.agent.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mirror.agent.entity.SysUser;
import org.apache.ibatis.annotations.Mapper;

/**
 * SysUser Mapper。
 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {
}
