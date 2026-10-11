package com.mirror.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mirror.agent.entity.UserProfile;
import com.mirror.agent.repository.UserProfileMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户画像版本管理服务：每次更新生成新版画像，支持纵向回溯对比。
 */
@Service
@RequiredArgsConstructor
public class ProfileService {

    private final UserProfileMapper profileMapper;

    /** 保存新版本画像（version 自动递增） */
    public UserProfile saveNewVersion(UserProfile profile) {
        UserProfile latest = getLatest(profile.getUserId());
        profile.setVersion(latest == null ? 1 : latest.getVersion() + 1);
        profile.setCreateTime(LocalDateTime.now());
        profileMapper.insert(profile);
        return profile;
    }

    public UserProfile getLatest(Long userId) {
        return profileMapper.selectOne(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, userId)
                .orderByDesc(UserProfile::getVersion)
                .last("LIMIT 1"));
    }

    public List<UserProfile> listVersions(Long userId) {
        return profileMapper.selectList(Wrappers.<UserProfile>lambdaQuery()
                .eq(UserProfile::getUserId, userId)
                .orderByDesc(UserProfile::getVersion));
    }
}
