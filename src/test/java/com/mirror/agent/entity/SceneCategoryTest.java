package com.mirror.agent.entity;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * 场景分类枚举测试：19 类白名单完整性 + 解析/归一语义。
 */
class SceneCategoryTest {

    /** 需求锁定的分类清单（顺序无关，内容必须完全一致） */
    private static final List<String> SPEC_LABELS = List.of(
            "生存安全", "工作职业", "学习成长", "家庭亲子", "亲密关系", "社交人际", "经济财务",
            "身心健康", "居住环境", "休闲兴趣", "自我实现", "社会文化", "法律安全", "科技信息",
            "时间人生", "意外丧失", "信仰意义", "照护责任", "其他");

    @Test
    void labelsMatchSpecExactly() {
        List<String> actual = SceneCategory.labels();
        assertEquals(19, actual.size());
        assertEquals(SPEC_LABELS.stream().sorted().toList(),
                actual.stream().sorted().toList(), "分类清单必须与需求完全一致");
    }

    @Test
    void joinedLabelsForPrompt() {
        String joined = SceneCategory.joinedLabels();
        for (String label : SPEC_LABELS) {
            org.junit.jupiter.api.Assertions.assertTrue(joined.contains(label), "prompt 注入应含: " + label);
        }
        assertEquals(19, joined.split("、").length);
    }

    @Test
    void fromLabelExactTrimmedMatch() {
        assertSame(SceneCategory.WORK_CAREER, SceneCategory.fromLabel("工作职业"));
        assertSame(SceneCategory.OTHER, SceneCategory.fromLabel("  其他 "));
        assertNull(SceneCategory.fromLabel("职场发展"), "越界值不模糊匹配");
        assertNull(SceneCategory.fromLabel(null));
        assertNull(SceneCategory.fromLabel("   "));
    }

    @Test
    void normalizeLabelSemantics() {
        assertEquals("工作职业", SceneCategory.normalizeLabel(" 工作职业"), "trim 归一");
        assertEquals("其他", SceneCategory.normalizeLabel("太空探索"), "越界兜底为其他");
        assertNull(SceneCategory.normalizeLabel(null), "缺失返回 null（调用方保留原值）");
        assertNull(SceneCategory.normalizeLabel(""), "空白返回 null");
    }
}
