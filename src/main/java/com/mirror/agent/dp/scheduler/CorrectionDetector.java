package com.mirror.agent.dp.scheduler;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 用户纠正信号快速识别（事件链路 v2 拆分通道触发器，纯规则零模型调用）：
 * 命中「不是这个/理解错了」等否定归因话术 → 把近期存疑归入（ambiguous）的情绪行放回挂起队列，
 * 等待后续事件重新绑定；模糊归因只松不紧，误报代价仅为一次多余的挂起。
 * 与 {@link FragmentDetector} 同属输入侧高置信规则通道，不产生任何额外模型调用。
 */
@Component
public class CorrectionDetector {

    /** 否定归因话术：用户对「情绪/事件关联错了」的显式纠正 */
    private static final Set<String> CORRECTION_MARKERS = Set.of(
            "不是这个", "不是这件事", "不是那件事", "不是这样", "不是因为这个", "不关这件事",
            "跟这件事无关", "跟这个没关系", "我说的不是", "理解错了", "你理解错了", "搞错了", "弄错了");

    public boolean isCorrection(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return CORRECTION_MARKERS.stream().anyMatch(text::contains);
    }
}
