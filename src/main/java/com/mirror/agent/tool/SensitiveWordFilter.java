package com.mirror.agent.tool;

import com.mirror.agent.config.ComplianceProperties;
import com.mirror.agent.message.ComplianceResult.ViolationLevel;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地敏感词过滤器（一级校验，DFA 字典树，毫秒级）。
 * 词库按分节标注违规级别，支持热更新（{@link #reload()}）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordFilter {

    /** 词命中结果 */
    public record Hit(String word, ViolationLevel level) {
    }

    private static final class TrieNode {
        final Map<Character, TrieNode> children = new HashMap<>();
        ViolationLevel level;
    }

    private final ComplianceProperties complianceProperties;

    private volatile TrieNode root = new TrieNode();

    @PostConstruct
    public void init() {
        reload();
    }

    /** 重新加载词库（热更新入口） */
    public void reload() {
        TrieNode newRoot = new TrieNode();
        int count = 0;
        try (BufferedReader reader = openDict()) {
            ViolationLevel sectionLevel = ViolationLevel.MILD;
            String line;
            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                if (trimmed.startsWith("#")) {
                    sectionLevel = parseSection(trimmed, sectionLevel);
                    continue;
                }
                insert(newRoot, trimmed, sectionLevel);
                count++;
            }
        } catch (IOException e) {
            log.error("敏感词库加载失败: {}", complianceProperties.getWordDictPath(), e);
        }
        this.root = newRoot;
        log.info("敏感词库加载完成, 词条数={}", count);
    }

    /** 全文扫描，返回所有命中的敏感词（最长匹配） */
    public List<Hit> findHits(String text) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return hits;
        }
        for (int i = 0; i < text.length(); i++) {
            int matchedEnd = -1;
            ViolationLevel matchedLevel = null;
            TrieNode node = root;
            for (int j = i; j < text.length(); j++) {
                node = node.children.get(text.charAt(j));
                if (node == null) {
                    break;
                }
                if (node.level != null) {
                    matchedEnd = j;
                    matchedLevel = node.level;
                }
            }
            if (matchedEnd >= 0) {
                hits.add(new Hit(text.substring(i, matchedEnd + 1), matchedLevel));
                i = matchedEnd;
            }
        }
        return hits;
    }

    private void insert(TrieNode node, String word, ViolationLevel level) {
        for (char c : word.toCharArray()) {
            node = node.children.computeIfAbsent(c, k -> new TrieNode());
        }
        node.level = level;
    }

    /** 分节注释决定该节词条的默认违规级别 */
    private ViolationLevel parseSection(String comment, ViolationLevel current) {
        if (comment.contains("高危")) {
            return ViolationLevel.SEVERE;
        }
        if (comment.contains("医疗") || comment.contains("违法")) {
            return ViolationLevel.MODERATE;
        }
        if (comment.contains("负面")) {
            return ViolationLevel.MILD;
        }
        return current;
    }

    private BufferedReader openDict() throws IOException {
        Resource resource = new DefaultResourceLoader().getResource(complianceProperties.getWordDictPath());
        return new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));
    }
}
