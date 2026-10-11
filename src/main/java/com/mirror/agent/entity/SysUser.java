package com.mirror.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户表 sys_user（docs/data_model.md §4.2.1）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 用户 ID，雪花算法生成 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 登录用户名 */
    private String username;

    /** 用户昵称 */
    private String nickname;

    /** 加密密码 */
    private String password;

    /** 账号状态：0 正常 1 禁用 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer isDeleted;
}
