package com.mirror.agent.util;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Markdown 报告渲染工具（docs/agent_implementation.md §3.7：Markdown 全文 + JSON 指标双输出）。
 */
@Component
public class MarkdownReportRenderer {

    /** 报告章节 */
    public record Section(String heading, List<String> lines) {
    }

    /** 报告骨架模型 */
    public record ReportModel(String title, String period, String summary, List<Section> sections) {
    }

    private static final String DISCLAIMER =
            "> 本报告由自我镜像智能体基于您的历史对话数据自动生成，仅供参考，不构成医疗诊断或建议。"
                    + "如需专业帮助，请咨询专业心理健康从业者。";

    /**
     * 渲染完整 Markdown 报告。
     */
    public String render(ReportModel model) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# ").append(model.title()).append("\n\n");
        markdown.append("**统计周期**：").append(model.period()).append("\n\n");
        if (model.summary() != null && !model.summary().isBlank()) {
            markdown.append("## 核心摘要\n\n").append(model.summary()).append("\n\n");
        }
        for (Section section : model.sections()) {
            markdown.append("## ").append(section.heading()).append("\n\n");
            for (String line : section.lines()) {
                markdown.append(line).append('\n');
            }
            markdown.append('\n');
        }
        markdown.append("---\n\n").append(DISCLAIMER).append('\n');
        return markdown.toString();
    }

    /** 渲染标签频次表格行：| 标签 | 次数 | 占比 | */
    public String tagTableRow(String tagName, long count, long total) {
        String percent = total == 0 ? "-" : String.format("%.1f%%", count * 100.0 / total);
        return "| " + tagName + " | " + count + " | " + percent + " |";
    }
}
