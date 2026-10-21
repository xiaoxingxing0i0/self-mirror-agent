package com.mirror.agent.report.pdf;

import com.mirror.agent.config.ReportProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PDF 层单元测试：%PDF 头、非空产物、中文字体（classpath OTF）真实嵌入并可抽取文本、流式输出。
 */
class PdfConverterTest {

    private final PdfConverter converter = new PdfConverter(new ReportProperties());

    private static final String XHTML = """
            <!DOCTYPE html>
            <html xmlns="http://www.w3.org/1999/xhtml">
            <head><meta charset="UTF-8" />
            <style>
              @page { size: A4; margin: 2cm; }
              body { font-family: "Noto Sans CJK SC"; }
            </style>
            </head>
            <body>
            <h1>中文测试报告</h1>
            <p>情绪分布：焦虑 32%，平静 55%</p>
            <br/>
            </body>
            </html>
            """;

    @Test
    void producesNonEmptyPdf() {
        byte[] pdf = converter.convert(XHTML);
        assertTrue(pdf.length > 1000, "PDF 过小不可能是有效产物: " + pdf.length);
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
    }

    @Test
    void chineseTextIsEmbeddedAndExtractable() throws IOException {
        byte[] pdf = converter.convert(XHTML);
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("中文测试报告"), "中文标题应可从 PDF 抽取，实际: " + text);
            assertTrue(text.contains("情绪分布"), "中文正文应可从 PDF 抽取，实际: " + text);
        }
    }

    @Test
    void streamsToOutputStream() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        converter.convert(XHTML, out);
        byte[] pdf = out.toByteArray();
        assertEquals("%PDF", new String(pdf, 0, 4, java.nio.charset.StandardCharsets.US_ASCII));
        try (PDDocument doc = PDDocument.load(new ByteArrayInputStream(pdf))) {
            assertEquals(1, doc.getNumberOfPages());
        }
    }

    @Test
    void missingFontFailsFastWithActionableMessage() {
        ReportProperties p = new ReportProperties();
        p.setFontClasspath("fonts/definitely-not-here.ttf");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> new PdfConverter(p));
        assertTrue(e.getMessage().contains("fonts/definitely-not-here.ttf"), "信息应含缺失路径");
        assertTrue(e.getMessage().contains("README"), "信息应含修复指引");
    }
}
