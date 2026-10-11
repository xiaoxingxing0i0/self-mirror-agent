package com.mirror.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.entity.SysUser;
import com.mirror.agent.repository.SysUserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * 用户注册 / 登录 / 令牌服务。令牌存 Redis（24h），私有数据按 user_id 隔离。
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private static final String TOKEN_KEY_PREFIX = "mirror:token:";
    private static final Duration TOKEN_TTL = Duration.ofHours(24);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SysUserMapper userMapper;
    private final RedisTemplate<String, Object> redisTemplate;

    public SysUser register(String username, String password, String nickname) {
        Long exists = userMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username));
        if (exists != null && exists > 0) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }
        SysUser user = new SysUser();
        user.setUsername(username);
        user.setNickname(nickname == null ? username : nickname);
        user.setPassword(hashPassword(password));
        user.setStatus(0);
        user.setCreateTime(LocalDateTime.now());
        user.setUpdateTime(LocalDateTime.now());
        userMapper.insert(user);
        return user;
    }

    /** 登录成功返回访问令牌 */
    public String login(String username, String password) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username));
        if (user == null || !verifyPassword(password, user.getPassword())) {
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        if (user.getStatus() != null && user.getStatus() == 1) {
            throw new BizException(ErrorCode.USER_DISABLED);
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        redisTemplate.opsForValue().set(TOKEN_KEY_PREFIX + token,
                String.valueOf(user.getId()), TOKEN_TTL);
        return token;
    }

    /** 令牌 → 用户 ID，失效抛未登录异常 */
    public Long resolveUserId(String token) {
        Object userId = redisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return Long.valueOf(userId.toString());
    }

    public void logout(String token) {
        redisTemplate.delete(TOKEN_KEY_PREFIX + token);
    }

    /** 密码存储格式：salt$sha256(salt+password) */
    private String hashPassword(String password) {
        byte[] saltBytes = new byte[16];
        RANDOM.nextBytes(saltBytes);
        String salt = HexFormat.of().formatHex(saltBytes);
        return salt + "$" + sha256(salt + password);
    }

    private boolean verifyPassword(String password, String stored) {
        if (stored == null || !stored.contains("$")) {
            return false;
        }
        String salt = stored.substring(0, stored.indexOf('$'));
        return MessageDigest.isEqual(sha256(salt + password).getBytes(StandardCharsets.UTF_8),
                stored.substring(salt.length() + 1).getBytes(StandardCharsets.UTF_8));
    }

    private String sha256(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "SHA-256 算法不可用", e);
        }
    }
}
