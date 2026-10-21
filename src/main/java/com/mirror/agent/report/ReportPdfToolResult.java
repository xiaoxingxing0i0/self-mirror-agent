package com.mirror.agent.report;

/**
 * {@code generate_report_pdf} 工具的结构化返回（以 JSON 字符串形式回给 Agent，绝不抛异常穿透）。
 *
 * @param success      是否成功
 * @param errorCode    失败时的业务错误码（如 207003 契约违规、100001 模板 ID 非法、500001 系统异常）；成功时为 null
 * @param message      成功时为简要结果说明；失败时为原因（契约违规含全部错误路径）
 * @param downloadPath 成功时为 PDF 文件绝对路径（下载标识）；失败时为 null
 * @param pdfBytes     成功时为 PDF 字节数；失败时为 null
 */
public record ReportPdfToolResult(
        boolean success,
        String errorCode,
        String message,
        String downloadPath,
        Long pdfBytes) {

    /**
     * 成功结果。
     *
     * @param downloadPath PDF 落盘绝对路径
     * @param pdfBytes     字节数
     */
    public static ReportPdfToolResult ok(String downloadPath, long pdfBytes) {
        return new ReportPdfToolResult(true, null,
                "PDF 已生成并落盘", downloadPath, pdfBytes);
    }

    /**
     * 失败结果。
     *
     * @param errorCode 业务错误码
     * @param message   失败原因（应可指导 Agent 修正入参后重试）
     */
    public static ReportPdfToolResult fail(String errorCode, String message) {
        return new ReportPdfToolResult(false, errorCode, message, null, null);
    }
}
