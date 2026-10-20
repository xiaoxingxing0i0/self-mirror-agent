package com.mirror.agent.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 事件现状摘要生成结果（封稿时由 facts 列表一次性生成，事件链路 v2）。
 */
@Data
public class EventSummaryResult implements Serializable {

    /** 现状摘要（≤200字，只由 facts 撰写） */
    private String summary;
}
