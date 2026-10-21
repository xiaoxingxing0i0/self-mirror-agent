package com.mirror.agent.message;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 语言分析结构化输出中的单个事由草案（事由抽取，并入 analyze 一次输出）。
 *
 * <p>事由链路 v2「事实稳定层」语义：facts 只承载【本条消息贡献的增量原子事实】，
 * 禁止合并旧信息重写（逐轮重写摘要会漂移）；现状摘要由封稿时对本事由全部
 * mention.fact_content 归并一次性生成。
 * 情绪归因不再由模型回溯指认（原 backfillMessageIds 已删），
 * 先说情绪后说事由的消息由后端挂起队列在事由落库时自动绑定。</p>
 */
@Data
public class IncidentDraft implements Serializable {

    /** 事由名称（≤15字） */
    private String name;

    /** 本条消息贡献的增量原子事实（1-3 条，每条 ≤50 字；NEW 事由即事由现状） */
    private List<String> facts = new ArrayList<>();

    /** 核心要素：time / participants / topic（事由同一性判定依据） */
    private Map<String, Object> coreFacts = new HashMap<>();

    /**
     * 同一性判定动作：NEW 新事由 | CONTINUE 归入已有 open 事由 |
     * AMBIGUOUS 拿不准先归入（merge-bias，ambiguous 标记落 incident_mention，封稿复核可拆分）。
     * CONTINUATION/REKINDLED 细分由后端 episode 时间规则判定，不由模型给。
     */
    private String incidentAction;

    /** 归入的既有事由 ID（CONTINUE / AMBIGUOUS 必填，取自注入的【最近提及事由】#id；NEW 为 null） */
    private Long refIncidentId;

    /** 本条消息提及其他「不同事由」的引用关系（对比/因果/单纯提及；同一事由续说不在此，走 incidentAction） */
    private List<ReferencedIncident> referencedIncidents = new ArrayList<>();

    /** 本事由携带的情绪码（引用 tags 中的 emotion code，可为空=无情绪事由） */
    private List<String> emotionCodes = new ArrayList<>();

    /** 心理特征码 */
    private List<String> psychologyCodes = new ArrayList<>();

    /** 归因置信度 */
    private double confidence;

    public static final String ACTION_NEW = "NEW";
    public static final String ACTION_CONTINUE = "CONTINUE";
    public static final String ACTION_AMBIGUOUS = "AMBIGUOUS";

    /** 查重检索用文本：名称 + 核心要素（与摘要解耦，facts 追加不影响检索稳定性） */
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

    /** 事由间引用（不同事由）：refIncidentId 必须是注入列表中的真实 #id */
    @Data
    public static class ReferencedIncident implements Serializable {

        /** 被引用的旧事由 ID */
        private Long refIncidentId;

        /** 引用类型：comparison 对比 | causal 因果 | mention 单纯提及 */
        private String refType;

        /** 引用原文片段（≤15字，如「比上次好」） */
        private String refText;
    }
}
