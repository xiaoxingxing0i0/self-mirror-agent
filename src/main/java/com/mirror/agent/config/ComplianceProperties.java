package com.mirror.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 合规检查配置（mirror.compliance.*）。
 */
@Data
@ConfigurationProperties(prefix = "mirror.compliance")
public class ComplianceProperties {

    /** 本地敏感词库路径（classpath: 或文件系统路径），支持热更新 */
    private String wordDictPath = "classpath:compliance/sensitive-words.txt";

    /** 是否持久化风险拦截日志 */
    private boolean riskLogEnabled = true;
}
