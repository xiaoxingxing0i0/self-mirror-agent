package com.mirror.agent.dp.scheduler;

import com.mirror.agent.config.AgentProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 消息完整性判定（并入语言分析方案）：
 * 本类仅保留「明显碎片快速通道」——高置信规则命中即直接归集等待，省掉一次语言分析；
 * 规则不命中的消息一律照常进入标准链路，由语言分析 Agent 结构化输出的 fragment 字段
 * 做全量完整性判定（见 prompts/language-analysis.txt），不产生任何额外模型调用。
 */
@Component
@RequiredArgsConstructor
public class FragmentDetector {

    /** 悬切结尾词：以这些词收尾几乎必然是话未说完（高置信，快速通道专用） */
    private static final Set<String> DANGLING_SUFFIXES = Set.of(
            "时候", "的时候", "但是", "可是", "而且", "然后", "接着", "因为", "所以", "如果", "假如",
            "虽然", "尽管", "以及", "还有", "就是", "不过", "关于", "对于", "或者", "要么",
            "我的", "你的", "他的", "她的", "这个", "那个", "这样", "那样", "其实", "其中", "原因是");

    /** 以连接性标点结尾视为明显碎片（逗号/顿号/分号/冒号，半全角） */
    private static final String CONNECTOR_TAIL = "，,、；;：:";

    private final AgentProperties agentProperties;

    /** 高置信规则初筛：命中即可跳过语言分析直接归集；不命中不代表完整（完整与否交给 fragment 字段） */
    public boolean isObviousFragment(String content) {
        if (!agentProperties.isFragmentAggregationEnabled()) {
            return false;
        }
        String text = content == null ? "" : content.strip();
        if (text.isEmpty()) {
            return false;
        }
        for (String suffix : DANGLING_SUFFIXES) {
            if (text.endsWith(suffix)) {
                return true;
            }
        }
        return CONNECTOR_TAIL.indexOf(text.charAt(text.length() - 1)) >= 0;
    }
}
