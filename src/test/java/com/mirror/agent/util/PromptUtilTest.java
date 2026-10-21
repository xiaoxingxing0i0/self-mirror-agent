package com.mirror.agent.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prompt 模板拆分后的渲染冒烟测试：模板可加载、{{key}} 占位替换正确、
 * user 模板中保留的 JSON 花括号不受影响（自研替换，非 StringTemplate）。
 */
class PromptUtilTest {

    private final PromptUtil promptUtil = new PromptUtil();

    @Test
    @DisplayName("summarize.txt：临时摘要 Agent 模板渲染")
    void renderSummarize() {
        String rendered = promptUtil.render("summarize.txt",
                Map.of("instruction", "压缩为100字以内", "text", "今天和同事聊了很久"));

        assertThat(rendered).contains("压缩为100字以内").contains("今天和同事聊了很久");
        assertThat(rendered).doesNotContain("{{");
    }

    @Test
    @DisplayName("compliance-check.txt：角色首行已拆出，JSON 输出格式保留在 user 模板")
    void renderComplianceCheck() {
        String rendered = promptUtil.render("compliance-check.txt",
                Map.of("textType", "OUTPUT", "content", "你需要吃药治疗"));

        assertThat(rendered).doesNotContain("你是内容合规审查引擎");   // 角色句已迁往 system 文件
        assertThat(rendered).contains("\"passed\"");                  // JSON 格式说明保留
        assertThat(rendered).contains("OUTPUT").contains("你需要吃药治疗");
        assertThat(rendered).doesNotContain("{{");
    }

    @Test
    @DisplayName("language-analysis.txt：静态规则已迁往 system 文件，user 模板仅保留动态注入段")
    void renderLanguageAnalysis() {
        String rendered = promptUtil.render("language-analysis.txt",
                Map.of("history", "（无历史对话）", "recentIncidents", "（无最近提及事由）", "input", "今天好累"));

        assertThat(rendered).doesNotContain("你是语言分析引擎");       // 角色句已迁往 system 文件
        assertThat(rendered).doesNotContain("## 标签字典").doesNotContain("## 示例A"); // 字典/格式/规则/示例已迁往 system 文件
        // 段落标题与 system 文件规则4/5 的交叉引用保持一致，改动需两侧同步
        assertThat(rendered).contains("## 最近提及事由").contains("## 会话上下文").contains("## 用户输入");
        assertThat(rendered).contains("（无最近提及事由）").contains("（无历史对话）").contains("今天好累");
        assertThat(rendered).doesNotContain("{{");
    }

    @Test
    @DisplayName("chat-comfort.txt：场景模板不受拆分影响")
    void renderChatComfort() {
        String rendered = promptUtil.render("chat-comfort.txt",
                Map.of("emotionTags", "sad", "retrievalContext", "（无）",
                        "history", "（无历史对话）", "recentIncidents", "（无）", "input", "我很难过"));

        assertThat(rendered).contains("场景：情绪安抚").contains("我很难过");
        assertThat(rendered).doesNotContain("{{");
    }
}
