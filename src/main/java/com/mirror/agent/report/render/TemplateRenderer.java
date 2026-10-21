package com.mirror.agent.report.render;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import freemarker.cache.TemplateLoader;
import freemarker.core.XMLOutputFormat;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Locale;
import java.util.Map;

/**
 * 模板渲染器（渲染层）：FreeMarker {@link Configuration} 单例，UTF-8，XML 输出格式（适配 XHTML 转义）。
 *
 * <p>确定性设置：Locale.ROOT、number_format=computer、模板异常直接抛出（fail-fast）。
 * 模板加载通过注入的 {@link TemplateSource} 扩展点，缓存由 FreeMarker 管理。</p>
 */
@Slf4j
@Component
public class TemplateRenderer {

    private final Configuration configuration;
    private final TemplateSource templateSource;

    /**
     * 构建渲染器并初始化 FreeMarker 单例配置。
     *
     * @param templateSource 模板源（默认注入 {@link ClasspathTemplateSource}）
     */
    public TemplateRenderer(TemplateSource templateSource) {
        this.templateSource = templateSource;
        TemplateLoader loader = templateSource.createTemplateLoader();
        Configuration cfg = new Configuration(Configuration.VERSION_2_3_32);
        cfg.setTemplateLoader(loader);
        cfg.setDefaultEncoding("UTF-8");
        // 输出格式 XML：插值按 XML 规则转义（&lt; &amp; 等），模板字面量原样输出 → 适配严格 XHTML
        cfg.setOutputFormat(XMLOutputFormat.INSTANCE);
        cfg.setURLEscapingCharset("UTF-8");
        // 确定性：固定 Locale 与数字格式，不受部署 JVM 影响
        cfg.setLocale(Locale.ROOT);
        cfg.setNumberFormat("computer");
        this.configuration = cfg;
    }

    /**
     * 按 templateId 加载模板并渲染为 HTML 字符串。
     *
     * @param templateId 模板 ID（如 {@code "report"}，解析规则见 {@link TemplateSource#resolveName(String)}）
     * @param model      渲染模型（来自组装层；value 需为 String/Map/List 等 FreeMarker 可处理类型）
     * @return 渲染产物 HTML（XHTML）字符串
     * @throws BizException 模板不存在 / 模板语法或数据模型错误 / IO 失败（REPORT_GENERATE_FAILED）
     */
    public String render(String templateId, Map<String, Object> model) {
        String name = templateSource.resolveName(templateId);
        try {
            Template template = configuration.getTemplate(name);
            StringWriter out = new StringWriter(8192);
            template.process(model, out);
            return out.toString();
        } catch (TemplateNotFoundException e) {
            log.warn("模板不存在: {} (resolved={})", templateId, name);
            throw new BizException(ErrorCode.REPORT_GENERATE_FAILED,
                    "报告模板不存在: templateId=" + templateId + " → " + name, e);
        } catch (TemplateException e) {
            // 模板名 + 行列号 + 原始 message，保证日志可直接定位问题
            log.warn("模板渲染失败: templateId={}, template={}, line={}, col={}, msg={}",
                    templateId, e.getTemplateName(), e.getLineNumber(), e.getColumnNumber(), e.getMessage());
            throw new BizException(ErrorCode.REPORT_GENERATE_FAILED,
                    "报告模板渲染失败: templateId=" + templateId
                            + ", line=" + e.getLineNumber() + ", col=" + e.getColumnNumber()
                            + ", " + e.getMessage(), e);
        } catch (IOException e) {
            log.warn("模板读取 IO 失败: templateId={}", templateId, e);
            throw new BizException(ErrorCode.REPORT_GENERATE_FAILED,
                    "报告模板读取失败: templateId=" + templateId, e);
        }
    }
}
