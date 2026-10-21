package com.mirror.agent.report.render;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染层单元测试：XML 转义、确定性、模板不存在/非法 ID 的错误语义。
 * 依赖 src/test/resources/templates/unit-test.ftl（独立于正式样例模板）。
 */
class TemplateRendererTest {

    private final TemplateRenderer renderer = new TemplateRenderer(new ClasspathTemplateSource());

    private static Map<String, Object> model(String title) {
        return Map.of("title", title, "summary", "sum", "sections", List.of());
    }

    @Test
    void rendersTemplateWithXmlEscaping() {
        String html = renderer.render("unit-test", model("A<b>&x"));
        assertTrue(html.contains("A&lt;b&gt;&amp;x"), "数据插值必须转义: " + html);
        assertTrue(html.startsWith("<!DOCTYPE html>"), "模板字面量原样输出");
        assertTrue(html.contains("（无章节）"), "空 sections 走占位分支");
    }

    @Test
    void rendersListData() {
        Map<String, Object> m = Map.of(
                "title", "t", "summary", "s",
                "sections", List.of(Map.of("heading", "第一章", "rows", List.of())));
        String html = renderer.render("unit-test", m);
        assertTrue(html.contains("<li>第一章</li>"));
        assertFalse(html.contains("（无章节）"));
    }

    @Test
    void renderingIsDeterministic() {
        assertEquals(renderer.render("unit-test", model("x")), renderer.render("unit-test", model("x")));
    }

    @Test
    void missingTemplateFailsWithGenerateError() {
        BizException e = assertThrows(BizException.class,
                () -> renderer.render("no-such-template", model("x")));
        assertEquals(ErrorCode.REPORT_GENERATE_FAILED, e.getErrorCode());
        assertTrue(e.getMessage().contains("no-such-template"), e.getMessage());
    }

    @Test
    void illegalTemplateIdRejected() {
        for (String bad : List.of("../secret", "A B", "a".repeat(65), "")) {
            BizException e = assertThrows(BizException.class,
                    () -> renderer.render(bad, model("x")), "应拒绝: " + bad);
            assertEquals(ErrorCode.PARAM_INVALID, e.getErrorCode());
        }
        assertThrows(BizException.class, () -> renderer.render(null, model("x")));
    }

    @Test
    void templateErrorExposesLineAndColumn() {
        // 引用模型中不存在的表达式 → FreeMarker 报错，异常信息应带定位信息
        Map<String, Object> m = Map.of("title", "t");
        BizException e = assertThrows(BizException.class, () -> renderer.render("unit-test", m));
        assertEquals(ErrorCode.REPORT_GENERATE_FAILED, e.getErrorCode());
        assertTrue(e.getMessage().contains("line="), e.getMessage());
    }
}
