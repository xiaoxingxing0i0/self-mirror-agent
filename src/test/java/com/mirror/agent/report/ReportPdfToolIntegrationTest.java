package com.mirror.agent.report;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.ReportProperties;
import com.mirror.agent.report.assemble.ReportModelAssembler;
import com.mirror.agent.report.contract.ContractViolationException;
import com.mirror.agent.report.contract.ContractValidator;
import com.mirror.agent.report.pdf.PdfConverter;
import com.mirror.agent.report.render.ClasspathTemplateSource;
import com.mirror.agent.report.render.TemplateRenderer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 全链路集成测试：report-sample.json → 契约校验 → 组装 → 模板 → PDF。
 * 纯手工装配五层，不启动 Spring 上下文、不依赖网络/DB/Redis；PDF 落盘 target/ 供人工检查。
 */
class ReportPdfToolIntegrationTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static ReportPdfTool tool;
    private static String sampleJson;

    @BeforeAll
    static void setUp() throws IOException {
        ReportProperties props = new ReportProperties();
        tool = new ReportPdfTool(
                new ContractValidator(),
                new ReportModelAssembler(FIXED_CLOCK, props),
                new TemplateRenderer(new ClasspathTemplateSource()),
                new PdfConverter(props),
                props);
        try (InputStream in = ReportPdfToolIntegrationTest.class.getResourceAsStream("/templates/report-sample.json")) {
            assertTrue(in != null, "示例契约资源缺失: /templates/report-sample.json");
            sampleJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void fullPipelineProducesValidPdfAndWritesToTarget() throws IOException {
        byte[] pdf = tool.render(sampleJson, null); // null → 默认模板 report
        assertTrue(pdf.length > 2000, "PDF 过小: " + pdf.length);
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));

        // 落盘便于人工检查渲染效果
        Path out = Paths.get("target", "report-sample.pdf");
        Files.createDirectories(out.getParent());
        Files.write(out, pdf);

        try (PDDocument doc = PDDocument.load(new java.io.ByteArrayInputStream(pdf))) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("十月自我镜像分析报告"), "中文标题应写入 PDF，实际: " + text);
            assertTrue(text.contains("情绪分布"), "章节标题应写入 PDF");
            assertTrue(text.contains("32%"), "数据值应写入 PDF");
            assertTrue(text.contains("严重"), "枚举展示名（非 name()）应写入 PDF");
        }
    }

    @Test
    void streamVariantMatchesSyncVariantInContent() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        tool.renderTo(sampleJson, "report", out);
        byte[] pdf = out.toByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4, StandardCharsets.US_ASCII));
        try (PDDocument doc = PDDocument.load(new java.io.ByteArrayInputStream(pdf))) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("深夜对话次数"));
        }
    }

    @Test
    void contractViolationPropagatesAsBizException() {
        String bad = "{\"title\": \"x\"}";
        ContractViolationException e = assertThrows(ContractViolationException.class,
                () -> tool.render(bad, null));
        assertEquals(ErrorCode.REPORT_CONTRACT_VIOLATION, e.getErrorCode());
        assertTrue(e.getErrorPaths().stream().anyMatch(p -> p.contains("reportId")));
    }

    @Test
    void unknownTemplateIdFailsWithGenerateError() {
        BizException e = assertThrows(BizException.class, () -> tool.render(sampleJson, "no-such-tpl"));
        assertEquals(ErrorCode.REPORT_GENERATE_FAILED, e.getErrorCode());
    }
}
