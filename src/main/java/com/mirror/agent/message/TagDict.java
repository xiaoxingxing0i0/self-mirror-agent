package com.mirror.agent.message;

import java.util.Map;
import java.util.Set;

/**
 * 标签数字编码字典（与 prompts/language-analysis.txt 标签字典保持一致的唯一事实源）。
 *
 * <p>编码在模型输出与库表（tag_record.tag_code）中均为字符串形式（如 "13"）。
 * 此处只收敛路由 / 归因需要硬编码比对的编码，展示名由模型输出的 name 字段与前端字典承担。</p>
 */
public final class TagDict {

    private TagDict() {
    }

    /** intent：1闲聊 2咨询 3查历史 4任务（primaryIntent 同用此编码） */
    public static final String INTENT_CHAT = "1";
    public static final String INTENT_CONSULT = "2";
    public static final String INTENT_QUERY = "3";
    public static final String INTENT_TASK = "4";

    /** emotion 1-14 中的负面子集：7焦虑 8低落 9愤怒 10疲惫 11孤独 12压力 13悲伤 */
    public static final Set<String> NEGATIVE_EMOTIONS =
            Set.of("7", "8", "9", "10", "11", "12", "13");

    /** task 编码 → 报告类型：1日报 2周报 3月报 4自定义报告 5数据统计 */
    public static final Map<String, String> TASK_CODE_TO_REPORT_TYPE = Map.of(
            "1", "daily",
            "2", "weekly",
            "3", "monthly",
            "4", "custom",
            "5", "statistic");
}
