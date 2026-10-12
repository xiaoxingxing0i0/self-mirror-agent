package com.mirror.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Agent 调度行为配置（mirror.agent.*），链路规则可配置化（docs/agent_implementation.md §3.1）。
 */
@Data
@ConfigurationProperties(prefix = "mirror.agent")
public class AgentProperties {

    /** 会话上下文 Redis 过期时间（小时） */
    private int sessionTtlHours = 24;

    /** 对话上下文截取最近 N 轮 */
    private int contextMaxRounds = 8;

    /** 标签置信度阈值，低于则标记待确认且不写入正式画像库 */
    private double lowConfidenceThreshold = 0.7;

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
}
