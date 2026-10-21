package com.mirror.agent.report.render;

import freemarker.cache.TemplateLoader;

/**
 * 模板源扩展点：屏蔽"模板从哪来"（classpath / 未来的 DB、OSS）。
 *
 * <p>新增模板源时实现本接口并注册为 Bean 即可，{@link TemplateRenderer} 不感知具体存储。
 * 模板内容缓存交给 FreeMarker 的 {@link TemplateLoader} 机制，实现类不必自管缓存。</p>
 */
public interface TemplateSource {

    /**
     * 创建 FreeMarker 底层模板加载器。
     *
     * @return 与 {@link #resolveName(String)} 产出的名称同源一致的 loader
     */
    TemplateLoader createTemplateLoader();

    /**
     * templateId → 模板资源名（如 {@code "report" -> "report.ftl"}）。
     *
     * <p><b>必须</b>对 id 做格式校验，杜绝路径穿越（如 {@code ../../}）。</p>
     *
     * @param templateId 模板标识
     * @return 可直接传给 {@link TemplateLoader#findTemplateSource(String)} 的名称
     * @throws com.mirror.agent.common.BizException templateId 非法
     */
    String resolveName(String templateId);
}
