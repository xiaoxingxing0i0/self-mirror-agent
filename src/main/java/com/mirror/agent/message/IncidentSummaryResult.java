package com.mirror.agent.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 事由现状摘要生成结果（封稿时由 facts 列表一次性生成，事由链路 v2）。
 */
@Data
public class IncidentSummaryResult implements Serializable {

    /** 现状摘要（≤200字，只由 facts 撰写） */
    private String summary;

    /** 场景分类：19 类中文标签之一（{@code com.mirror.agent.entity.SceneCategory}）；缺失为 null */
    private String scene;
}
