package com.mirror.agent.report;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.ReportProperties;
import com.mirror.agent.report.assemble.ReportModelAssembler;
import com.mirror.agent.report.contract.ContractValidator;
import com.mirror.agent.report.contract.ReportContract;
import com.mirror.agent.report.pdf.PdfConverter;
import com.mirror.agent.report.render.TemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * JSON 报告契约 → 模板渲染 → PDF 生成 工具（Agent 服务的确定性 Tool）。
 *
 * <p>串联四层，全链路无随机性、无外部服务依赖：</p>
 * <ol>
 *   <li>{@link ContractValidator} —— JSON Schema（Draft 2020-12）校验 + DTO 反序列化；</li>
 *   <li>{@link ReportModelAssembler} —— 展示层格式化（null 归一/日期/枚举稳定名/空集合）；</li>
 *   <li>{@link TemplateRenderer} —— FreeMarker 渲染 XHTML（模板与代码解耦）；</li>
 *   <li>{@link PdfConverter} —— openhtmltopdf 转 PDF（classpath 中文字体）。</li>
 * </ol>
 *
 * <p>异常统一为 {@link BizException}：契约违规 {@code REPORT_CONTRACT_VIOLATION}（含错误路径）、
 * 模板/渲染问题 {@code REPORT_GENERATE_FAILED}、参数非法 {@code PARAM_INVALID}，
 * 由现有 GlobalExceptionHandler 转为标准响应。</p>
 */
@Slf4j
@Component
public class ReportPdfTool {

    private final ContractValidator contractValidator;
    private final ReportModelAssembler modelAssembler;
    private final TemplateRenderer templateRenderer;
    private final PdfConverter pdfConverter;
    private final ReportProperties properties;

    /**
     * @param contractValidator 契约层
     * @param modelAssembler    组装层
     * @param templateRenderer  渲染层
     * @param pdfConverter      PDF 层
     * @param properties        模块配置（默认模板等）
     */
    public ReportPdfTool(ContractValidator contractValidator,
                         ReportModelAssembler modelAssembler,
                         TemplateRenderer templateRenderer,
                         PdfConverter pdfConverter,
                         ReportProperties properties) {
        this.contractValidator = contractValidator;
        this.modelAssembler = modelAssembler;
        this.templateRenderer = templateRenderer;
        this.pdfConverter = pdfConverter;
        this.properties = properties;
    }

    /**
     * 使用默认模板渲染。
     *
     * @param contractJson 报告契约 JSON（见 report.schema.json；示例 report-sample.json）
     * @return PDF 字节数组（以 {@code %PDF} 开头）
     * @throws BizException 校验/模板/渲染任一环节失败
     */
    public byte[] render(String contractJson) {
        return render(contractJson, null);
    }

    /**
     * 按契约与模板 ID 生成 PDF。
     *
     * @param contractJson 报告契约 JSON（见 report.schema.json；示例 report-sample.json）
     * @param templateId   模板 ID（classpath:/templates/{id}.ftl）；null/空白 → 配置默认模板
     * @return PDF 字节数组（以 {@code %PDF} 开头）
     * @throws BizException 契约违规（含全部错误路径）/ 模板非法或不存在 / 渲染转换失败
     */
    public byte[] render(String contractJson, String templateId) {
        String tid = (templateId == null || templateId.isBlank())
                ? properties.getDefaultTemplate() : templateId;
        long start = System.nanoTime();

        ReportContract contract = contractValidator.validate(contractJson);
        Map<String, Object> model = modelAssembler.assemble(contract);
        String xhtml = templateRenderer.render(tid, model);
        byte[] pdf = pdfConverter.convert(xhtml);

        log.info("报告 PDF 生成完成: reportId={}, template={}, pages(htmlLen)={}, pdfBytes={}, costMs≈{}",
                contract.reportId(), tid, xhtml.length(), pdf.length,
                (System.nanoTime() - start) / 1_000_000);
        return pdf;
    }

    /**
     * 按契约渲染并流式写出（大报告/下载场景，避免整份 PDF 驻留内存）。
     *
     * @param contractJson 报告契约 JSON
     * @param templateId   模板 ID；null/空白 → 默认模板
     * @param target       输出流（本方法不关闭）
     * @throws BizException 任一环节失败（语义同 {@link #render(String, String)}）
     */
    public void renderTo(String contractJson, String templateId, java.io.OutputStream target) {
        String tid = (templateId == null || templateId.isBlank())
                ? properties.getDefaultTemplate() : templateId;

        ReportContract contract = contractValidator.validate(contractJson);
        Map<String, Object> model = modelAssembler.assemble(contract);
        String xhtml = templateRenderer.render(tid, model);
        pdfConverter.convert(xhtml, target);

        log.info("报告 PDF 流式输出完成: reportId={}, template={}", contract.reportId(), tid);
    }
}
