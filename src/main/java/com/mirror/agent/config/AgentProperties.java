package com.mirror.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 调度行为配置（mirror.agent.*），链路规则可配置化（docs/agent_implementation.md §3.1）。
 */
@Data
@ConfigurationProperties(prefix = "mirror.agent")
public class AgentProperties {

    /** 对话上下文截取最近 N 轮 */
    private int contextMaxRounds = 8;

    /** 标签置信度阈值，低于则标记待确认且不写入正式画像库 */
    private double lowConfidenceThreshold = 0.8;

    /** 合规不通过时最大重生成次数 */
    private int maxRegenerateTimes = 2;

    /** 子任务失败默认重试次数 */
    private int subTaskMaxRetry = 2;

    /** 检索默认 TopK */
    private int retrievalTopK = 5;

    /** 碎片归集总开关：用户话未说完时等待续说，超时由 Agent 主动追问 */
    private boolean fragmentAggregationEnabled = true;

    /** 碎片归集滑动窗口（秒）：每收到一个新碎片，截止时间重置为 now + 该值 */
    private int fragmentWindowSeconds = 60;

    /** 单轮归集总等待上限（秒）：自首碎片起超过该时长，立即合并并强制澄清追问 */
    private int fragmentMaxTotalWaitSeconds = 180;

    /** 单轮最多合并碎片数：超过立即合并处理 */
    private int fragmentMaxFragments = 5;

    /** 情绪-事件关联链路总开关（关闭=忽略 analyze 的 events 输出，不落事件数据） */
    private boolean eventPipelineEnabled = true;

    /** 查重高置信阈值：rerank 候选的向量相似度 ≥ 该值直接归并为既有事件 */
    private double eventDedupHighScore = 0.92;

    /** 查重低置信阈值：介于上下阈值之间走 LLM 同事件裁决，低于则新建 */
    private double eventDedupLowScore = 0.80;

    /** rerank 时间衰减常数 τ（天）：exp(-Δt/τ) */
    private int eventTimeDecayTauDays = 7;

    /** episode 间隔（分钟）：距上次提及超过该时长再提 = 重提（REKINDLED），否则连续（CONTINUATION） */
    private int eventEpisodeGapMinutes = 30;

    /** EMERGING 事件无新增提及后的定稿时间（分钟） */
    private int eventFinalizeMinutes = 20;

    /** 上下文注入的最近提及事件个数 */
    private int eventContextCount = 3;

    /** 事件查重向量检索 TopK */
    private int eventSearchTopk = 5;

    /** 事实层单事件强制封稿阈值：提及行数达到即立即定稿（防 open 草稿事实无限膨胀） */
    private int eventSealMaxMentions = 15;

    /** 单用户同时处于 EMERGING 的事件上限：超出强制封稿最活跃最旧的一个 */
    private int eventMaxOpenDrafts = 3;

    /** 情绪挂起队列窗口（分钟）：事件落库时仅归因该时间窗内未关联事件的情绪/心理标签 */
    private int emotionPendingWindowMinutes = 15;
}
