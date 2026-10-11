package com.mirror.agent.message;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 语言分析 Agent 结构化输出（JSON Schema 约束）。
 */
@Data
public class LanguageAnalysisResult implements Serializable {

    /** 结构化标签集：情绪 / 意图 / 心理 / 任务 */
    private List<TagItem> tags = new ArrayList<>();

    /** 语义核心摘要 */
    private String summary;

    /** 主意图（DP 调度路由依据）：chat / consult / query / task */
    private String primaryIntent;

    /** 是否存在待确认（低置信度）标签，需引导用户澄清 */
    private boolean needClarify;

    public List<TagItem> tagsOf(String category) {
        return tags.stream().filter(t -> category.equals(t.getCategory())).toList();
    }

    public String firstTagCode(String category) {
        return tags.stream()
                .filter(t -> category.equals(t.getCategory()))
                .map(TagItem::getCode)
                .findFirst()
                .orElse(null);
    }
}
