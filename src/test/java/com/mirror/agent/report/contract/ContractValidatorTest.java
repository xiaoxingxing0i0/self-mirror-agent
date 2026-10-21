package com.mirror.agent.report.contract;

import com.mirror.agent.report.contract.ReportContract.Row;
import com.mirror.agent.report.contract.ReportContract.Section;
import com.mirror.agent.report.contract.ReportContract.Status;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 契约层单元测试：校验通过/失败路径、错误路径完整性、DTO 映射。
 */
class ContractValidatorTest {

    private final ContractValidator validator = new ContractValidator();

    private static final String VALID = """
            {
              "reportId": "r-001",
              "title": "十月画像报告",
              "generatedAt": "2026-10-21T10:00:00Z",
              "summary": "整体平稳",
              "sections": [
                {
                  "heading": "情绪分布",
                  "rows": [
                    {"label": "焦虑占比", "value": "32%", "status": "WARN"},
                    {"label": "平静占比", "value": "55%", "status": "OK"}
                  ]
                }
              ]
            }
            """;

    @Test
    void validContractDeserializes() {
        ReportContract contract = validator.validate(VALID);
        assertEquals("r-001", contract.reportId());
        assertEquals("十月画像报告", contract.title());
        assertEquals(Instant.parse("2026-10-21T10:00:00Z"), contract.generatedAt());
        assertEquals("整体平稳", contract.summary());
        List<Section> sections = contract.sections();
        assertNotNull(sections);
        assertEquals(1, sections.size());
        Row row = sections.get(0).rows().get(0);
        assertEquals(Status.WARN, row.status());
    }

    @Test
    void missingRequiredFieldFailsWithErrorPath() {
        String json = """
                {"title": "t", "generatedAt": "2026-10-21T10:00:00Z"}
                """;
        ContractViolationException e = assertThrows(ContractViolationException.class,
                () -> validator.validate(json));
        assertTrue(e.getErrorPaths().stream().anyMatch(p -> p.contains("reportId")),
                "错误路径应包含 reportId，实际: " + e.getErrorPaths());
        assertTrue(e.getMessage().contains("reportId"), "异常信息应包含错误路径");
    }

    @Test
    void wrongTypeFailsWithErrorPath() {
        String json = """
                {"reportId": 123, "title": "t", "generatedAt": "2026-10-21T10:00:00Z"}
                """;
        ContractViolationException e = assertThrows(ContractViolationException.class,
                () -> validator.validate(json));
        assertTrue(e.getErrorPaths().stream().anyMatch(p -> p.contains("reportId")),
                "类型错误应报告 reportId 路径，实际: " + e.getErrorPaths());
    }

    @Test
    void badStatusEnumAndNestedPathReported() {
        String json = """
                {
                  "reportId": "r", "title": "t", "generatedAt": "2026-10-21T10:00:00Z",
                  "sections": [{"heading": "h", "rows": [{"label": "l", "status": "BOOM"}]}]
                }
                """;
        ContractViolationException e = assertThrows(ContractViolationException.class,
                () -> validator.validate(json));
        assertTrue(e.getMessage().contains("status"), "应定位到 status 字段: " + e.getMessage());
        assertTrue(e.getMessage().contains("[0]"), "嵌套路径应含数组下标: " + e.getMessage());
        assertEquals(1, e.getErrorPaths().size());
    }

    @Test
    void multipleErrorsAllReported() {
        String json = """
                {"summary": "only null-ish fields"}
                """;
        ContractViolationException e = assertThrows(ContractViolationException.class,
                () -> validator.validate(json));
        // reportId / title / generatedAt 三个必填字段缺失都要报告
        assertTrue(e.getErrorPaths().size() >= 3, "应聚合全部错误，实际: " + e.getErrorPaths());
    }

    @Test
    void unknownFieldRejected() {
        String json = """
                {"reportId": "r", "title": "t", "generatedAt": "2026-10-21T10:00:00Z", "oops": 1}
                """;
        assertThrows(ContractViolationException.class, () -> validator.validate(json));
    }

    @Test
    void nullSummaryAndEmptySectionsAreValid() {
        String json = """
                {
                  "reportId": "r", "title": "t", "generatedAt": "2026-10-21T10:00:00Z",
                  "summary": null, "sections": []
                }
                """;
        ReportContract contract = validator.validate(json);
        assertNull(contract.summary());
        assertTrue(contract.sections() != null && contract.sections().isEmpty());
    }

    @Test
    void blankOrMalformedInputFails() {
        assertThrows(ContractViolationException.class, () -> validator.validate("  "));
        assertThrows(ContractViolationException.class, () -> validator.validate("{not-json"));
    }
}
