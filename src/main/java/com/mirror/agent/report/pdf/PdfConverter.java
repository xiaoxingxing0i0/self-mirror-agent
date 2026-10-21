package com.mirror.agent.report.pdf;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.ReportProperties;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * HTML→PDF 转换器（PDF 层，openhtmltopdf + PDFBox）。
 *
 * <p>中文字体在构造期一次性从 {@code classpath:/fonts/} 读入缓存（禁止 {@code new File} 读资源，
 * 保证 jar 内可运行）；字体缺失直接抛出带放置指引的异常。
 * 注册字体名取 {@link ReportProperties#getFontFamily()}，<b>必须</b>与模板 CSS 的 font-family 完全一致。</p>
 *
 * <p>说明：PDF 元数据含生成时间戳，故两次转换字节流不保证逐字节相同；
 * "确定性"指同一输入产生相同版面与文本内容。</p>
 */
@Slf4j
@Component
public class PdfConverter {

    private final ReportProperties properties;
    private final byte[] fontBytes;

    /**
     * 加载并缓存中文字体字节（fail-fast：资源缺失在此处即暴露）。
     *
     * @param properties 报告模块配置（字体 classpath 与注册名）
     * @throws IllegalStateException 字体资源缺失或读取失败，异常信息含期望路径与修复指引
     */
    public PdfConverter(ReportProperties properties) {
        this.properties = properties;
        this.fontBytes = loadFontBytes(properties.getFontClasspath());
        log.info("PDF 字体已加载: classpath:{}, 注册名={}, {} bytes",
                properties.getFontClasspath(), properties.getFontFamily(), fontBytes.length);
    }

    /**
     * 转换 XHTML 为 PDF 字节数组（异步/落盘场景）。
     *
     * @param xhtml 合法的 XHTML 字符串（模板渲染产物）
     * @return 非空 PDF 字节数组（以 {@code %PDF} 开头）
     * @throws BizException 渲染失败（REPORT_GENERATE_FAILED）
     */
    public byte[] convert(String xhtml) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        convert(xhtml, out);
        return out.toByteArray();
    }

    /**
     * 转换 XHTML 并流式写入目标输出流（同步下载场景，不整份驻留内存）。
     *
     * @param xhtml  合法的 XHTML 字符串
     * @param target 目标输出流（本方法不关闭它）
     * @throws BizException 渲染失败（REPORT_GENERATE_FAILED）
     */
    public void convert(String xhtml, OutputStream target) {
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        builder.useFont(() -> new ByteArrayInputStream(fontBytes), properties.getFontFamily());
        builder.withHtmlContent(xhtml, null);
        builder.toStream(target);
        try {
            builder.run();
            target.flush();
        } catch (IOException | RuntimeException e) {
            log.warn("PDF 生成失败: htmlLength={}, err={}", xhtml.length(), e.getMessage());
            throw new BizException(ErrorCode.REPORT_GENERATE_FAILED,
                    "PDF 生成失败: " + e.getMessage(), e);
        }
    }

    /**
     * 从 classpath 读取字体字节。路径配置不含前导斜杠，故用 ClassLoader 资源加载。
     */
    private static byte[] loadFontBytes(String classpathLocation) {
        ClassLoader cl = PdfConverter.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(classpathLocation)) {
            if (in == null) {
                throw new IllegalStateException(
                        "中文字体缺失：classpath 未找到 '" + classpathLocation + "'。"
                                + "请将 Noto Sans SC（OFL）放置于 src/main/resources/" + classpathLocation
                                + " 并重新构建，或调整配置 mirror.report.font-classpath。详见报告模块 README。");
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("中文字体读取失败: " + classpathLocation, e);
        }
    }
}
