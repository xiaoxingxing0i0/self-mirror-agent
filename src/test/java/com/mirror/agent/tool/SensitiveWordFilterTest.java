package com.mirror.agent.tool;

import com.mirror.agent.config.ComplianceProperties;
import com.mirror.agent.message.ComplianceResult.ViolationLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 敏感词过滤器单元测试（不依赖 Spring 上下文与外部中间件）。
 */
class SensitiveWordFilterTest {

    private SensitiveWordFilter filter;

    @BeforeEach
    void setUp() {
        ComplianceProperties properties = new ComplianceProperties();
        filter = new SensitiveWordFilter(properties);
        filter.reload();
    }

    @Test
    void cleanTextHasNoHits() {
        assertTrue(filter.findHits("今天天气不错，我去公园散步了").isEmpty());
    }

    @Test
    void highRiskWordIsSevere() {
        List<SensitiveWordFilter.Hit> hits = filter.findHits("我最近总是睡不着");
        assertTrue(hits.isEmpty(), "普通负面表达不应命中词库");

        List<SensitiveWordFilter.Hit> riskHits = filter.findHits("我不想活了");
        assertEquals(1, riskHits.size());
        assertEquals(ViolationLevel.SEVERE, riskHits.get(0).level());
    }

    @Test
    void medicalDiagnosisWordIsModerate() {
        List<SensitiveWordFilter.Hit> hits = filter.findHits("我认为你确诊抑郁症了");
        assertEquals(1, hits.size());
        assertEquals(ViolationLevel.MODERATE, hits.get(0).level());
    }

    @Test
    void negativeGuidanceWordIsMild() {
        List<SensitiveWordFilter.Hit> hits = filter.findHits("别难过，活着没意思只是暂时的");
        assertEquals(1, hits.size());
        assertEquals(ViolationLevel.MILD, hits.get(0).level());
    }
}
