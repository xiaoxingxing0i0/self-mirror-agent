package com.mirror.agent.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.ReportProperties;
import com.mirror.agent.report.assemble.ReportModelAssembler;
import com.mirror.agent.report.contract.ContractValidator;
import com.mirror.agent.report.pdf.PdfConverter;
import com.mirror.agent.report.render.ClasspathTemplateSource;
import com.mirror.agent.report.render.TemplateRenderer;
import com.mirror.agent.tool.FileGenerationTool;
import com.mirror.agent.util.JsonUtil;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * generate_report_pdf 工具单测：@Tool/@ToolParam 元数据完整性（名称、描述覆盖契约与返回语义、
 * 可选性、参数名可发现性）与入参校验/异常收敛的结构化结果。
 */
class ReportPdfAgentToolTest {

    private static final String OUTPUT_DIR = "target/agent-tool-test";

    private static ReportPdfAgentTool agentTool;
    private static String sampleJson;

    @BeforeAll
    static void setUp() throws IOException {
        ReportProperties props = new ReportProperties();
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        ReportPdfTool pdfTool = new ReportPdfTool(
                new ContractValidator(),
                new ReportModelAssembler(clock, props),
                new TemplateRenderer(new ClasspathTemplateSource()),
                new PdfConverter(props),
                props);
        agentTool = new ReportPdfAgentTool(pdfTool, new FileGenerationTool(OUTPUT_DIR));
        try (InputStream in = ReportPdfAgentToolTest.class.getResourceAsStream("/templates/report-sample.json")) {
            assertNotNull(in, "示例契约缺失");
            sampleJson = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /* ---------- 工具描述与参数元数据 ---------- */

    @Test
    void toolAnnotationMetadataIsComplete() throws NoSuchMethodException {
        Method method = ReportPdfAgentTool.class
                .getMethod("generateReportPdf", String.class, String.class);
        Tool tool = method.getAnnotation(Tool.class);
        assertNotNull(tool, "@Tool 必须存在");
        assertEquals("generate_report_pdf", tool.name());
        // 描述必须讲清返回语义（成功字段 + 错误码），指导模型正确处理结果
        assertTrue(tool.description().contains("downloadPath"), "描述应含成功返回字段: " + tool.description());
        assertTrue(tool.description().contains("errorCode"), "描述应含失败返回字段");
        assertTrue(tool.description().contains("207003"), "描述应解释契约错误码");

        Parameter[] params = method.getParameters();
        assertEquals(2, params.length, "仅暴露 contractJson + templateId 两个入参");
        // Spring AI 用参数名生成 JSON Schema：依赖 -parameters 编译标志，此断言防止退化
        assertEquals("contractJson", params[0].getName());
        assertEquals("templateId", params[1].getName());

        ToolParam p0 = params[0].getAnnotation(ToolParam.class);
        ToolParam p1 = params[1].getAnnotation(ToolParam.class);
        assertNotNull(p0);
        assertNotNull(p1);
        assertTrue(p0.required(), "contractJson 必为必填");
        assertFalse(p1.required(), "templateId 应可选");
        // 契约描述需覆盖必填字段与 status 枚举，否则模型无法一次成功
        assertTrue(p0.description().contains("reportId"));
        assertTrue(p0.description().contains("generatedAt"));
        assertTrue(p0.description().contains("OK|WARN|CRITICAL"), "描述应写明 status 允许值");
    }

    /* ---------- 入参校验与异常收敛 ---------- */

    @Test
    void validContractReturnsSuccessAndWritesPdf() throws IOException {
        ReportPdfToolResult result = invoke(sampleJson, null);
        assertTrue(result.success(), () -> "应成功: " + result.message());
        assertNotNull(result.downloadPath());
        assertTrue(result.pdfBytes() > 0);
        Path pdf = Paths.get(result.downloadPath());
        assertTrue(Files.exists(pdf), "PDF 应已落盘: " + pdf);
        byte[] head = Files.readAllBytes(pdf);
        assertEquals("%PDF", new String(head, 0, 4, StandardCharsets.US_ASCII));
    }

    @Test
    void sameContractProducesSameArtifactName() {
        String r1 = agentTool.generateReportPdf(sampleJson, "report");
        String r2 = agentTool.generateReportPdf(sampleJson, "report");
        assertEquals(parse(r1).downloadPath(), parse(r2).downloadPath(), "文件名对契约确定");
    }

    @Test
    void contractViolationReturns207003WithPaths() {
        ReportPdfToolResult result = invoke("{\"title\": \"x\"}", null);
        assertFalse(result.success());
        assertEquals("207003", result.errorCode());
        assertTrue(result.message().contains("reportId"),
                "失败原因应含错误路径便于模型修正: " + result.message());
    }

    @Test
    void blankContractIsRejectedNotThrown() {
        ReportPdfToolResult result = invoke("  ", null);
        assertFalse(result.success());
        assertEquals("207003", result.errorCode());
    }

    @Test
    void illegalTemplateIdReturns100001() {
        ReportPdfToolResult result = invoke(sampleJson, "../etc");
        assertFalse(result.success());
        assertEquals("100001", result.errorCode());
    }

    @Test
    void unknownTemplateIdReturns207002() {
        ReportPdfToolResult result = invoke(sampleJson, "no-such-tpl");
        assertFalse(result.success());
        assertEquals("207002", result.errorCode());
    }

    @Test
    void unexpectedRuntimeIsConvergedTo500001() {
        ReportPdfTool broken = Mockito.mock(ReportPdfTool.class);
        Mockito.when(broken.render(Mockito.anyString(), Mockito.isNull()))
                .thenThrow(new IllegalStateException("模拟字体环境缺失"));
        ReportPdfAgentTool tool = new ReportPdfAgentTool(broken,
                new FileGenerationTool(OUTPUT_DIR));
        ReportPdfToolResult result = parse(tool.generateReportPdf(sampleJson, null));
        assertFalse(result.success());
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(), result.errorCode());
        assertTrue(result.message().contains("模拟字体环境缺失"));
    }

    /* ---------- helpers ---------- */

    private static ReportPdfToolResult invoke(String json, String templateId) {
        return parse(agentTool.generateReportPdf(json, templateId));
    }

    private static ReportPdfToolResult parse(String json) {
        return JsonUtil.fromJson(json, new TypeReference<ReportPdfToolResult>() {
        });
    }
}
