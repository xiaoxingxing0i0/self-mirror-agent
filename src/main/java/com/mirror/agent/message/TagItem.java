package com.mirror.agent.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 语言分析 Agent 输出的单个标签（三级标签体系，docs/agent_implementation.md §3.2）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TagItem implements Serializable {

    /** 标签大类：emotion / intent / psychology / task */
    public static final String CATEGORY_EMOTION = "emotion";
    public static final String CATEGORY_INTENT = "intent";
    public static final String CATEGORY_PSYCHOLOGY = "psychology";
    public static final String CATEGORY_TASK = "task";

    /** 标签大类 */
    private String category;

    /** 标签编码（标签字典内唯一） */
    private String code;

    /** 标签名称 */
    private String name;

    /** 置信度 0-1 */
    private double confidence;

    /** 低于阈值时标记为待确认，不写入正式画像库 */
    private boolean needConfirm;
}
