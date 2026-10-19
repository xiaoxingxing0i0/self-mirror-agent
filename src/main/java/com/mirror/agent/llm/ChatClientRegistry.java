package com.mirror.agent.llm;

import org.springframework.ai.chat.client.ChatClient;

import java.util.EnumMap;
import java.util.Map;

/**
 * 角色 × Provider → ChatClient 注册表。
 * 由 ChatClientConfig 在启动时装配（每个角色对两个 provider 各建一个 client，
 * 保证路由主通道、降级通道、显式 override 均可用）；ChatClient 无状态且线程安全。
 */
public class ChatClientRegistry {

    private final Map<AgentRole, Map<Provider, ChatClient>> clients = new EnumMap<>(AgentRole.class);
    private final Map<AgentRole, String> systemTexts = new EnumMap<>(AgentRole.class);

    public void register(AgentRole role, Provider provider, ChatClient client, String systemText) {
        clients.computeIfAbsent(role, r -> new EnumMap<>(Provider.class)).put(provider, client);
        systemTexts.putIfAbsent(role, systemText);
    }

    /** 取指定角色 + provider 的 ChatClient，未注册时 fail-fast */
    public ChatClient get(AgentRole role, Provider provider) {
        ChatClient client = clients.getOrDefault(role, Map.of()).get(provider);
        if (client == null) {
            throw new IllegalStateException("ChatClient 未注册: role=" + role + ", provider=" + provider);
        }
        return client;
    }

    /** 角色的完整 System Prompt 文本（persona + 角色文件），供网关追加 extraSystem 时重组 */
    public String systemText(AgentRole role) {
        String text = systemTexts.get(role);
        if (text == null) {
            throw new IllegalStateException("System Prompt 未注册: role=" + role);
        }
        return text;
    }
}
