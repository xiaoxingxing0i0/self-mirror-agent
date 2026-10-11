package com.mirror.agent.util;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prompt 模板工具：加载 classpath:prompts/*.txt 并做 {{var}} 占位符替换。
 * 自研轻量替换避免 StringTemplate 与 Prompt 中 JSON 花括号冲突。
 */
@Slf4j
@Component
public class PromptUtil {

    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /**
     * 渲染模板。
     *
     * @param templateName prompts/ 下的文件名，如 "chat-comfort.txt"
     * @param variables    占位符变量（模板中以 {{key}} 引用）
     */
    public String render(String templateName, Map<String, String> variables) {
        String template = cache.computeIfAbsent(templateName, this::load);
        String rendered = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}",
                    entry.getValue() == null ? "" : entry.getValue());
        }
        return rendered;
    }

    /** 清空模板缓存（模板热更新用） */
    public void evictCache() {
        cache.clear();
    }

    private String load(String templateName) {
        try {
            ClassPathResource resource = new ClassPathResource("prompts/" + templateName);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("Prompt 模板加载失败: {}", templateName, e);
            throw new BizException(ErrorCode.SYSTEM_ERROR, "Prompt 模板加载失败: " + templateName, e);
        }
    }
}
