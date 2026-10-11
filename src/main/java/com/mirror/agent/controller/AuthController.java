package com.mirror.agent.controller;

import com.mirror.agent.common.Result;
import com.mirror.agent.entity.SysUser;
import com.mirror.agent.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口：注册 / 登录 / 登出（免拦截路径 /api/auth/**）。
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;

    @Data
    public static class RegisterRequest {
        @NotBlank(message = "用户名不能为空")
        @Size(min = 3, max = 32, message = "用户名长度需在3-32之间")
        private String username;
        @NotBlank(message = "密码不能为空")
        @Size(min = 8, max = 64, message = "密码长度需在8-64之间")
        private String password;
        @Size(max = 32, message = "昵称最长32字符")
        private String nickname;
    }

    @Data
    public static class LoginRequest {
        @NotBlank(message = "用户名不能为空")
        private String username;
        @NotBlank(message = "密码不能为空")
        private String password;
    }

    public record LoginResponse(String token, Long userId, String nickname) {
    }

    @PostMapping("/register")
    public Result<Long> register(@Valid @RequestBody RegisterRequest request) {
        SysUser user = userService.register(
                request.getUsername(), request.getPassword(), request.getNickname());
        return Result.ok(user.getId());
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        String token = userService.login(request.getUsername(), request.getPassword());
        Long userId = userService.resolveUserId(token);
        return Result.ok(new LoginResponse(token, userId, request.getUsername()));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader("Authorization") String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            userService.logout(authorization.substring(7).trim());
        }
        return Result.ok();
    }
}
