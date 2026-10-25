package com.mirror.agent.report;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.tool.FileGenerationTool;
import com.mirror.agent.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * {@code generate_report_pdf}：把 {@link ReportPdfTool} 以 Spring AI {@code @Tool} 暴露给 Agent 循环。
 *
 * <p>契约要点：</p>
 * <ul>
 *   <li>入参仅 {@code contractJson}（字符串）+ 可选 {@code templateId}，不暴露内部 DTO/Map；</li>
 *   <li><b>任何异常都不穿透</b>：统一捕获后返回 {@link ReportPdfToolResult} 的 JSON 字符串
 *       （成功含 downloadPath/pdfBytes；失败含 errorCode/message），让模型可自行修正后重试；</li>
 *   <li>成功产物落盘（复用 {@code FileGenerationTool}，输出目录 {@code mirror.file.output-dir}），
 *       返回下载标识而非 base64——23KB+ 的 base64 进 LLM 上下文属纯浪费。</li>
 * </ul>
 *
 * <p>接入方式（Agent 侧）：
 * {@code MethodToolCallbackProvider.builder().toolObjects(reportPdfAgentTool).build().getToolCallbacks()}
 * 注册进 ChatClient 即可。</p>
 */
@Slf4j
@Component
public class ReportPdfAgentTool {

    /** 工具名（模型可见） */
    public static final String TOOL_NAME = "generate_report_pdf";

    private static final String CONTRACT_DESCRIPTION = """
            报告契约 JSON 字符串。必填字段：reportId(非空字符串)、title(非空字符串)、\
            generatedAt(yyyy-MM-dd格式 不带时区，如 2026-10-21 10:00:00)；\
            可选字段：summary(字符串或 null)、sections(数组或 null，每项含 heading 必填字符串 + \
            rows 数组，每行含 label 必填、value 字符串或 null、status 仅允许 OK|WARN|CRITICAL|null)。\
            不允许出现未定义的其它字段。""";

    private final ReportPdfTool reportPdfTool;
    private final FileGenerationTool fileGenerationTool;

    /**
     * @param reportPdfTool      同步 PDF 生成入口（四层串联）
     * @param fileGenerationTool 既有文件落盘组件（防路径穿越、输出目录配置）
     */
    public ReportPdfAgentTool(ReportPdfTool reportPdfTool, FileGenerationTool fileGenerationTool) {
        this.reportPdfTool = reportPdfTool;
        this.fileGenerationTool = fileGenerationTool;
    }

    /**
     * Agent 工具入口：校验契约 → 渲染 → 生成 PDF → 落盘，全程异常收敛为结构化 JSON 结果。
     *
     * <p>返回值 JSON 结构见 {@link ReportPdfToolResult}；错误码语义：
     * 207003 契约校验失败（message 内含全部错误字段路径，修正入参后重试）、
     * 100001 模板 ID 非法、207002 模板不存在或渲染/转换失败、500001 系统或写文件失败。</p>
     *
     * @param contractJson 报告契约 JSON（见 {@code report.schema.json}）
     * @param templateId   可选模板 ID（classpath:/templates/{id}.ftl）；缺省用默认模板 report
     * @return {@link ReportPdfToolResult} 序列化后的 JSON 字符串，永不为 null、永不抛异常
     */
    @Tool(name = TOOL_NAME, description = """
            依据 JSON 报告契约生成 PDF 报告文件并落盘。入参 contractJson：见参数说明；\
            入参 templateId：可选，默认模板 report。返回值是 JSON 字符串：成功时 \
            {"success":true,"downloadPath":"<PDF 文件绝对路径>","pdfBytes":<字节数>}，\
            失败时 {"success":false,"errorCode":"<错误码>","message":"<原因>"}，\
            错误码 207003=契约校验失败（message 含错误字段路径，请修正 JSON 后重试）、\
            100001=模板 ID 非法、207002=模板不存在或渲染失败、500001=系统异常。""")
    public String generateReportPdf(
            @ToolParam(description = CONTRACT_DESCRIPTION) String contractJson,
            @ToolParam(required = false,
                    description = "可选模板 ID（小写字母数字连字符，如 report），缺省使用默认模板 report") String templateId) {
        try {
            byte[] pdf = reportPdfTool.render(contractJson, templateId);
            String fileName = "report-" + sha256Hex(contractJson).substring(0, 12) + ".pdf";
            String path = fileGenerationTool.writeBytes(fileName, pdf);
            return JsonUtil.toJson(ReportPdfToolResult.ok(path, pdf.length));
        } catch (BizException e) {
            // 含 ContractViolationException（207003，message 已带全部错误路径）/ PARAM_INVALID / REPORT_GENERATE_FAILED
            log.warn("generate_report_pdf 业务失败: code={}, msg={}",
                    e.getErrorCode().getCode(), e.getMessage());
            return JsonUtil.toJson(ReportPdfToolResult.fail(
                    e.getErrorCode().getCode(), e.getMessage()));
        } catch (Exception e) {
            // 兜底：字体缺失 IllegalStateException 等一律收敛，绝不让异常穿透 Agent 循环
            log.error("generate_report_pdf 未知异常", e);
            return JsonUtil.toJson(ReportPdfToolResult.fail(
                    ErrorCode.SYSTEM_ERROR.getCode(),
                    "报告生成异常: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
    }

    /**
     * 文件名指纹：对契约取 SHA-256 前 12 位十六进制，保证同一契约产物名确定、不同契约不互相覆盖。
     */
    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然支持 SHA-256，属环境异常
            throw new BizException(ErrorCode.SYSTEM_ERROR, "SHA-256 不可用", e);
        }
    }
}
