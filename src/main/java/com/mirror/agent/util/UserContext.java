package com.mirror.agent.util;

/**
 * 当前登录用户上下文（ThreadLocal），由认证拦截器写入、请求结束时清理。
 * 横切关注点组件，禁止 new 实例化（硬性规则 2）。
 */
public final class UserContext {

    private static final ThreadLocal<Long> CURRENT_USER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void setUserId(Long userId) {
        CURRENT_USER.set(userId);
    }

    public static Long getUserId() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
