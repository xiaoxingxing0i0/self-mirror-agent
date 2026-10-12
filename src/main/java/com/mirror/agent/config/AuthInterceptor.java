package com.mirror.agent.config;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.service.UserService;
import com.mirror.agent.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 认证拦截器（横切关注点，经 Spring 注入注册，硬性规则 2）：
 * 解析 Bearer 令牌 → 写入 UserContext，请求结束清理，防止线程复用串号。
 */
@Component
@RequiredArgsConstructor
public class AuthInterceptor implements HandlerInterceptor {

    private static final String AUTH_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final UserService userService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String token = resolveToken(request);
        if (token == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        UserContext.setUserId(userService.resolveUserId(token));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        UserContext.clear();
    }

    /** Bearer 请求头优先；EventSource 无法携带自定义头，SSE 订阅类接口支持 ?token= 查询参数 */
    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(AUTH_HEADER);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            return token.isEmpty() ? null : token;
        }
        String param = request.getParameter("token");
        return param == null || param.isBlank() ? null : param.trim();
    }
}
