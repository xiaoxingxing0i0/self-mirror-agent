package com.mirror.agent.message;

import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * 一轮待合并的碎片缓冲（Redis: mirror:agg:buf:{conversationId}）。
 * 碎片原文即时落库 message 表（溯源），此处仅缓存待合并视图与窗口时间基准。
 */
@Data
public class FragmentBuffer implements Serializable {

    private Long conversationId;

    private Long userId;

    /** 本轮首个碎片到达时间（epoch 毫秒），滑动窗口总上限的基准 */
    private long firstTs;

    private List<Fragment> fragments = new ArrayList<>();

    public FragmentBuffer() {
    }

    public FragmentBuffer(Long conversationId, Long userId, long firstTs, List<Fragment> fragments) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.firstTs = firstTs;
        this.fragments = fragments;
    }

    /** 单个用户碎片：关联已落库的消息 ID + 原文 */
    @Data
    public static class Fragment implements Serializable {

        private Long messageId;

        private String content;

        private long ts;

        public Fragment() {
        }

        public Fragment(Long messageId, String content, long ts) {
            this.messageId = messageId;
            this.content = content;
            this.ts = ts;
        }
    }
}
