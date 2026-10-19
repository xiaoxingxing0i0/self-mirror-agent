package com.mirror.agent.message;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语言分析结构化输出中的单个事件草案（事件抽取与归因，并入 analyze 一次输出）。
 * summaryDraft 语义 = 该事件的「现状摘要」：延续同一事件时由模型合并旧摘要与新信息后整体重写。
 */
@Data
public class EventDraft implements Serializable {

    /** 事件名称（≤15字） */
    private String name;

    /** 事件现状摘要（≤200字） */
    private String summaryDraft;

    /** 核心要素：time / participants / topic（事件同一性判定依据） */
    private Map<String, Object> coreFacts = new HashMap<>();

    /** 上下文可指认的既有事件 ID（场景2：无需向量检索）；无则 null */
    private Long refEventId;

    /** 模型预判提及类型：NEW / CONTINUATION / REKINDLED（最终以后端 episode 时间规则为准） */
    private String mentionHint;

    /** 本事件携带的情绪码（引用 tags 中的 emotion code，可为空=无情绪事件） */
    private List<String> emotionCodes = new ArrayList<>();

    /** 心理特征码 */
    private List<String> psychologyCodes = new ArrayList<>();

    /** 归因置信度 */
    private double confidence;

    /**
     * 回溯归因：本事件可解释的历史「先说情绪未说事由」消息 id（只允许引用历史 [id=..] 标记）；
     * 后端按 user_id + 未归因 + emotion/psychology 三重校验后回填 tag_record.event_id。
     */
    private List<Long> backfillMessageIds = new ArrayList<>();

    /** 查重检索用文本：名称 + 核心要素（摘要↔摘要同分布检索） */
    public String queryText() {
        StringBuilder builder = new StringBuilder(name == null ? "" : name);
        if (coreFacts.get("time") != null) {
            builder.append(' ').append(coreFacts.get("time"));
        }
        if (coreFacts.get("participants") != null) {
            builder.append(' ').append(coreFacts.get("participants"));
        }
        if (coreFacts.get("topic") != null) {
            builder.append(' ').append(coreFacts.get("topic"));
        }
        return builder.toString().strip();
    }
}
