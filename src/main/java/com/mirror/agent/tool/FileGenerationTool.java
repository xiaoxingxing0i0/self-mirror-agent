package com.mirror.agent.tool;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 文件生成工具（工具 Agent 能力：生成报告文件等）。
 * 输出目录可通过 mirror.file.output-dir 配置。
 */
@Slf4j
@Component
public class FileGenerationTool {

    private final Path outputDir;

    public FileGenerationTool(@Value("${mirror.file.output-dir:./data/output}") String outputDir) {
        this.outputDir = Paths.get(outputDir).toAbsolutePath().normalize();
    }

    /**
     * 将文本内容写入输出目录，返回绝对路径。
     *
     * @param fileName 文件名（不允许路径穿越）
     * @param content  文本内容（如 Markdown 报告）
     */
    public String writeText(String fileName, String content) {
        Path target = outputDir.resolve(fileName).normalize();
        if (!target.startsWith(outputDir)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法文件名: " + fileName);
        }
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
            log.info("文件已生成: {}", target);
            return target.toString();
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "文件生成失败: " + fileName, e);
        }
    }
}
