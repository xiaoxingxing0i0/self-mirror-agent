package com.mirror.agent.report.assemble;

import com.mirror.agent.config.ReportProperties;
import com.mirror.agent.report.contract.ReportContract;
import com.mirror.agent.report.contract.ReportContract.Row;
import com.mirror.agent.report.contract.ReportContract.Section;
import com.mirror.agent.report.contract.ReportContract.Status;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渲染模型组装器（数据组装层）：{@link ReportContract} → FreeMarker 渲染模型 {@code Map<String,Object>}。
 *
 * <p>所有展示层格式化在本层完成，模板只做布局不做逻辑：</p>
 * <ul>
 *   <li>null 一律归一为空串（label/value/summary/heading 等）；</li>
 *   <li>generatedAt 按 {@link ReportProperties} 配置的格式与时区格式化为字符串；缺失时回退注入的 {@link Clock}；</li>
 *   <li>枚举输出稳定展示名与 CSS class（switch 穷尽匹配，新增枚举值会编译失败、强制同步映射，
 *       而不是依赖 {@code toString()/name()}）；</li>
 *   <li>null 集合输出为空列表（空态占位文案属于展示内容，由模板负责，不在 Java 硬编码）。</li>
 * </ul>
 *
 * <h2>模型 key 契约（模板依赖，勿随意改名）</h2>
 * <pre>
 * reportId, title, generatedAt, summary            —— 均为 String（已归一）
 * sections  —— List&lt;Map&gt;，每项：
 *   heading(String), rows(List&lt;Map&gt;)
 *   rows 每项：label(String), value(String), statusLabel(String), statusClass(String)
 * </pre>
 */
@Slf4j
@Component
public class ReportModelAssembler {

    /** 未知/缺失状态对应的稳定 CSS class（模板中定义为无色） */
    private static final String STATUS_CLASS_NONE = "none";

    private final Clock clock;
    private final ReportProperties properties;

    /**
     * Spring 装配入口：生产环境使用系统时钟。
     *
     * @param properties 报告模块配置
     */
    @Autowired
    public ReportModelAssembler(ReportProperties properties) {
        this(Clock.systemDefaultZone(), properties);
    }

    /**
     * 测试/自定义入口：注入固定 {@link Clock} 保证单测确定性。
     *
     * @param clock      时钟（generatedAt 缺失时的回退时间源）
     * @param properties 报告模块配置
     */
    public ReportModelAssembler(Clock clock, ReportProperties properties) {
        this.clock = clock;
        this.properties = properties;
    }

    /**
     * 将契约组装为渲染模型。
     *
     * @param contract 校验通过的契约（字段允许为 null，本方法负责归一）
     * @return 不可变视图的模型 Map，可直接交给模板渲染
     */
    public Map<String, Object> assemble(ReportContract contract) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("reportId", nullToEmpty(contract.reportId()));
        model.put("title", nullToEmpty(contract.title()));
        model.put("generatedAt", formatGeneratedAt(contract));
        model.put("summary", nullToEmpty(contract.summary()));
        model.put("sections", assembleSections(contract.sections()));
        return Map.copyOf(model);
    }

    private String formatGeneratedAt(ReportContract contract) {
        DateTimeFormatter formatter = DateTimeFormatter
                .ofPattern(properties.getDateFormat())
                .withZone(ZoneId.of(properties.getTimeZone()));
        if (contract.generatedAt() == null) {
            // schema 要求必填，走到这里说明是程序内构造的 DTO；用注入时钟兜底并告警
            log.warn("契约 generatedAt 为空，回退使用 Clock 当前时间 reportId={}", contract.reportId());
            return formatter.format(clock.instant());
        }
        return formatter.format(contract.generatedAt());
    }

    private List<Map<String, Object>> assembleSections(List<Section> sections) {
        if (sections == null) {
            return List.of();
        }
        return sections.stream().map(this::assembleSection).toList();
    }

    private Map<String, Object> assembleSection(Section section) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heading", nullToEmpty(section.heading()));
        m.put("rows", assembleRows(section.rows()));
        return Map.copyOf(m);
    }

    private List<Map<String, Object>> assembleRows(List<Row> rows) {
        if (rows == null) {
            return List.of();
        }
        return rows.stream().map(this::assembleRow).toList();
    }

    private Map<String, Object> assembleRow(Row row) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("label", nullToEmpty(row.label()));
        m.put("value", nullToEmpty(row.value()));
        m.put("statusLabel", statusLabel(row.status()));
        m.put("statusClass", statusClass(row.status()));
        return Map.copyOf(m);
    }

    /**
     * 状态展示名：穷尽 switch，不依赖 toString()/name()，枚举演化时由编译器强制更新映射。
     */
    private static String statusLabel(Status status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case OK -> "正常";
            case WARN -> "注意";
            case CRITICAL -> "严重";
        };
    }

    /**
     * 状态 CSS class：与模板中的着色样式名一一对应。
     */
    private static String statusClass(Status status) {
        if (status == null) {
            return STATUS_CLASS_NONE;
        }
        return switch (status) {
            case OK -> "ok";
            case WARN -> "warn";
            case CRITICAL -> "critical";
        };
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
