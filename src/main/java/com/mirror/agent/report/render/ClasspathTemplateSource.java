package com.mirror.agent.report.render;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import freemarker.cache.ClassTemplateLoader;
import freemarker.cache.TemplateLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.ClassUtils;

import java.util.regex.Pattern;

/**
 * classpath 模板源：模板固定位于 {@code classpath:/templates/{templateId}.ftl}。
 *
 * <p>templateId 仅允许小写字母/数字开头、可含连字符的短标识（最长 64），
 * 从源头杜绝路径穿越与任意 classpath 资源读取。</p>
 */
@Component
public class ClasspathTemplateSource implements TemplateSource {

    /** 模板根目录（classpath 内） */
    public static final String BASE_DIR = "/templates";

    /** 模板文件后缀约定 */
    public static final String SUFFIX = ".ftl";

    private static final Pattern TEMPLATE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    /**
     * {@inheritDoc}
     *
     * <p>基于 FreeMarker {@link ClassTemplateLoader}，jar 内/外均可读取，自带模板缓存。</p>
     */
    @Override
    public TemplateLoader createTemplateLoader() {
        ClassLoader cl = ClassUtils.getDefaultClassLoader() != null
                ? ClassUtils.getDefaultClassLoader()
                : ClasspathTemplateSource.class.getClassLoader();
        return new ClassTemplateLoader(cl, BASE_DIR);
    }

    /**
     * {@inheritDoc}
     *
     * @throws BizException templateId 为 null/空白或不匹配白名单格式（PARAM_INVALID）
     */
    @Override
    public String resolveName(String templateId) {
        if (templateId == null || !TEMPLATE_ID.matcher(templateId).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "非法模板 ID（要求 ^[a-z0-9][a-z0-9-]{0,63}$）: " + templateId);
        }
        return templateId + SUFFIX;
    }
}
