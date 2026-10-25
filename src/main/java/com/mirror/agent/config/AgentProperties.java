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

    /** 情绪-事由关联链路总开关（关闭=忽略 analyze 的 incidents 输出，不落事由数据） */
    private boolean incidentPipelineEnabled = true;

    /** 查重高置信阈值：rerank 候选的向量相似度 ≥ 该值直接归并为既有事由 */
    private double incidentDedupHighScore = 0.92;

    /** 查重低置信阈值：介于上下阈值之间走 LLM 同事由裁决，低于则新建 */
    private double incidentDedupLowScore = 0.80;

    /** rerank 时间衰减常数 τ（天）：exp(-Δt/τ) */
    private int incidentTimeDecayTauDays = 7;

    /** episode 间隔（分钟）：距上次提及超过该时长再提 = 重提（REKINDLED），否则连续（CONTINUATION） */
    private int incidentEpisodeGapMinutes = 30;

    /** EMERGING 事由无新增提及后的定稿时间（分钟） */
    private int incidentFinalizeMinutes = 20;

    /** 上下文注入的最近提及事由个数 */
    private int incidentContextCount = 3;

    /** 事由查重向量检索 TopK */
    private int incidentSearchTopk = 5;

    /** 事实层单事由强制封稿阈值：提及行数达到即立即定稿（防 open 草稿事实无限膨胀） */
    private int incidentSealMaxMentions = 15;

    /** 单用户同时处于 EMERGING 的事由上限：超出强制封稿最活跃最旧的一个 */
    private int incidentMaxOpenDrafts = 3;

    /** 情绪挂起队列窗口（分钟）：事由落库时仅归因该时间窗内未关联事由的情绪/心理标签 */
    private int emotionPendingWindowMinutes = 15;

    /** 报告周期澄清开关：custom/statistic 且用户未明示区间时反问一轮（关闭则直接按默认窗口建任务） */
    private boolean reportPeriodClarifyEnabled = true;

    /** 周期澄清待办槽 TTL（分钟）：超时后用户再答按新话题处理 */
    private int reportPeriodClarifyTtlMinutes = 10;

    /** 日历类锚点最大统计跨度（天）：超出截断 start；事由锚（INCIDENT）不受此限 */
    private int reportMaxSpanDays = 90;
}
