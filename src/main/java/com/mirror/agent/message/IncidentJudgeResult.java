package com.mirror.agent.message;

import lombok.Data;

import java.io.Serializable;

/**
 * 灰区同事由裁决结构化输出（查重相似度落在上下阈值之间时触发）。
 */
@Data
public class IncidentJudgeResult implements Serializable {

    /** 两个描述是否指向同一事由（时段+参与者+主题三要素基本一致） */
    private boolean same;
}
