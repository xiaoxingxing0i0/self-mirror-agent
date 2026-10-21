package com.mirror.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * PDF 报告模块配置（mirror.report.*）。
 *
 * <p>放在 config 包是遵循主类 {@code @ConfigurationPropertiesScan("com.mirror.agent.config")} 的扫描范围约定。</p>
 */
@Data
@ConfigurationProperties(prefix = "mirror.report")
public class ReportProperties {

    /** 未指定 templateId 时使用的默认模板 ID（对应 classpath:/templates/{id}.ftl） */
    private String defaultTemplate = "report";

    /** generatedAt 的展示格式（DateTimeFormatter pattern） */
    private String dateFormat = "yyyy-MM-dd HH:mm:ss";

    /** generatedAt 格式化使用的时区（ZoneId ID，如 Asia/Shanghai） */
    private String timeZone = "Asia/Shanghai";

    /** PDF 内嵌中文字体的 classpath 路径（相对于 classpath 根，不含前导 /） */
    private String fontClasspath = "fonts/NotoSansSC-VF.ttf";

    /** 注册到 PDF 与模板 CSS font-family 的字体名，两处必须一致 */
    private String fontFamily = "Noto Sans CJK SC";
}
