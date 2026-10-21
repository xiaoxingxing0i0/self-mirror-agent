package com.mirror.agent.report.assemble;

import com.mirror.agent.config.ReportProperties;
import com.mirror.agent.report.contract.ReportContract;
import com.mirror.agent.report.contract.ReportContract.Row;
import com.mirror.agent.report.contract.ReportContract.Section;
import com.mirror.agent.report.contract.ReportContract.Status;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组装层单元测试：null 归一、日期格式化（时区/格式可配）、枚举稳定展示名、空集合兜底、Clock 注入。
 */
class ReportModelAssemblerTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static ReportProperties props() {
        return new ReportProperties(); // 默认: yyyy-MM-dd HH:mm:ss / Asia/Shanghai
    }

    private static ReportContract contract(String summary, List<Section> sections) {
        return new ReportContract("r-1", "标题", Instant.parse("2026-10-21T10:00:00Z"), summary, sections);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> sectionsOf(Map<String, Object> model) {
        return (List<Map<String, Object>>) model.get("sections");
    }

    @Test
    void formatsDateWithConfiguredZoneAndPattern() {
        Map<String, Object> model = new ReportModelAssembler(FIXED_CLOCK, props())
                .assemble(contract("摘要", null));
        // 10:00 UTC → Asia/Shanghai(+8) 18:00
        assertEquals("2026-10-21 18:00:00", model.get("generatedAt"));
    }

    @Test
    void customPatternAndZoneAreHonored() {
        ReportProperties p = props();
        p.setDateFormat("yyyy年MM月dd日");
        p.setTimeZone("UTC");
        Map<String, Object> model = new ReportModelAssembler(FIXED_CLOCK, p)
                .assemble(contract("s", null));
        assertEquals("2026年10月21日", model.get("generatedAt"));
    }

    @Test
    void nullGeneratedAtFallsBackToInjectedClock() {
        ReportContract c = new ReportContract("r-2", "t", null, null, null);
        Map<String, Object> model = new ReportModelAssembler(FIXED_CLOCK, props()).assemble(c);
        // 固定时钟 2026-01-01T00:00Z → Asia/Shanghai 08:00
        assertEquals("2026-01-01 08:00:00", model.get("generatedAt"));
    }

    @Test
    void nullsNormalizedToEmptyStrings() {
        ReportContract c = new ReportContract(null, null, Instant.parse("2026-10-21T10:00:00Z"), null, null);
        Map<String, Object> model = new ReportModelAssembler(FIXED_CLOCK, props()).assemble(c);
        assertEquals("", model.get("reportId"));
        assertEquals("", model.get("title"));
        assertEquals("", model.get("summary"));
        assertTrue(sectionsOf(model).isEmpty(), "null sections 应归一为不可变空列表");
    }

    @Test
    void emptySectionsAndRowsRenderAsEmptyLists() {
        ReportContract c = contract("", List.of(new Section("空章", List.of())));
        Map<String, Object> model = new ReportModelAssembler(FIXED_CLOCK, props()).assemble(c);
        List<Map<String, Object>> sections = sectionsOf(model);
        assertEquals(1, sections.size());
        assertEquals("空章", sections.get(0).get("heading"));
        assertInstanceOf(List.class, sections.get(0).get("rows"));
        assertTrue(((List<?>) sections.get(0).get("rows")).isEmpty());
    }

    @Test
    void statusEnumStableDisplayNamesAndClasses() {
        ReportContract c = contract("s", List.of(new Section("h", List.of(
                new Row("a", "1", Status.OK),
                new Row("b", null, Status.WARN),
                new Row("c", "3", Status.CRITICAL),
                new Row("d", "4", null)))));
        Map<String, Object> model = new ReportModelAssembler(FIXED_CLOCK, props()).assemble(c);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) sectionsOf(model).get(0).get("rows");

        assertEquals("正常", rows.get(0).get("statusLabel"));
        assertEquals("ok", rows.get(0).get("statusClass"));
        assertEquals("", rows.get(1).get("value"), "null value 归一为空串");
        assertEquals("注意", rows.get(1).get("statusLabel"));
        assertEquals("warn", rows.get(1).get("statusClass"));
        assertEquals("严重", rows.get(2).get("statusLabel"));
        assertEquals("critical", rows.get(2).get("statusClass"));
        assertEquals("", rows.get(3).get("statusLabel"));
        assertEquals("none", rows.get(3).get("statusClass"));
    }

    @Test
    void nullRowsInsideSectionNormalized() {
        ReportContract c = contract("s", List.of(new Section("h", null)));
        Map<String, Object> model = new ReportModelAssembler(FIXED_CLOCK, props()).assemble(c);
        assertTrue(((List<?>) sectionsOf(model).get(0).get("rows")).isEmpty());
    }
}
