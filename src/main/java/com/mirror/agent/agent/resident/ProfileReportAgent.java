package com.mirror.agent.agent.resident;

import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.entity.AnalysisReport;
import com.mirror.agent.entity.UserProfile;
import com.mirror.agent.llm.AgentRole;
import com.mirror.agent.llm.LlmGateway;
import com.mirror.agent.llm.LlmRequest;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.message.ComplianceResult;
import com.mirror.agent.repository.MessageMapper;
import com.mirror.agent.repository.TagRecordMapper.EmotionTrendStat;
import com.mirror.agent.repository.TagRecordMapper.TagStat;
import com.mirror.agent.service.ProfileService;
import com.mirror.agent.service.ReportService;
import com.mirror.agent.service.TagService;
import com.mirror.agent.util.JsonUtil;
import com.mirror.agent.util.MarkdownReportRenderer;
import com.mirror.agent.util.MarkdownReportRenderer.ReportModel;
import com.mirror.agent.util.MarkdownReportRenderer.Section;
import com.mirror.agent.util.PromptUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户画像与报告 Agent（docs/agent_definition.md §2.7）。
 * 画像仅基于对话标签数据聚合生成，不做过度推断；报告全文合规校验后落库。
 * 所有结论均可溯源到标签统计（tag_record → message 原文）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileReportAgent {

    /** 样本量低于该值时报告标注「数据有限，结论仅供参考」 */
    private static final int MIN_SAMPLE_COUNT = 20;

    private final TagService tagService;
    private final ProfileService profileService;
    private final ReportService reportService;
    private final MessageMapper messageMapper;
    private final ComplianceAgent complianceAgent;
    private final LlmGateway llmGateway;
    private final PromptUtil promptUtil;
    private final MarkdownReportRenderer reportRenderer;
    private final AgentProperties agentProperties;

    /** 定时画像更新：聚合当日正式标签 → 新版画像 */
    public AgentResult<UserProfile> buildProfile(Long userId, LocalDate statDate) {
        LocalDateTime start = statDate.atStartOfDay();
        LocalDateTime end = start.plusDays(1);
        List<TagStat> stats = tagService.statByRange(userId, start, end);
        long sampleCount = messageMapper.countUserMessages(userId, start, end);
        if (stats.isEmpty() && sampleCount == 0) {
            return AgentResult.fail(ErrorCode.PROFILE_DATA_INSUFFICIENT, "当日无对话样本，跳过画像更新");
        }
        UserProfile profile = new UserProfile();
        profile.setUserId(userId);
        profile.setStatDate(statDate);
        profile.setEmotionFeature(JsonUtil.toJson(featureOf(stats, "emotion")));
        profile.setPsychologyFeature(JsonUtil.toJson(featureOf(stats, "psychology")));
        profile.setBehaviorFeature(JsonUtil.toJson(behaviorFeature(userId, start, end)));
        profile.setSampleCount((int) sampleCount);
        return AgentResult.ok(profileService.saveNewVersion(profile));
    }

    /** 报告生成：拉取范围数据 → 统计 → LLM 撰写结论建议 → 合规校验 → 落库 */
    public AgentResult<AnalysisReport> generateReport(Long userId, String reportType,
                                                      LocalDate startDate, LocalDate endDate,
                                                      Long taskId) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TagStat> stats = tagService.statByRange(userId, start, end);
        List<EmotionTrendStat> trend = tagService.statEmotionTrend(userId, start, end);
        long sampleCount = messageMapper.countUserMessages(userId, start, end);
        String llmSections = renderByLlm(reportType, startDate, endDate, stats, trend, userId, sampleCount);
        ReportModel model = buildModel(reportType, startDate, endDate, stats, trend,
                sampleCount, llmSections);
        String content = reportRenderer.render(model);
        ComplianceResult compliance = complianceAgent.checkReport(content).getData();
        if (compliance != null && !compliance.isPassed()) {
            log.warn("报告合规校验未通过: userId={}, violation={}", userId, compliance.getViolationType());
            return AgentResult.fail(ErrorCode.COMPLIANCE_OUTPUT_REJECT, "报告内容合规校验未通过");
        }
        AnalysisReport report = assembleReport(userId, reportType, startDate, endDate,
                content, stats, trend, sampleCount, taskId);
        return AgentResult.ok(reportService.save(report));
    }

    private String renderByLlm(String reportType, LocalDate startDate, LocalDate endDate,
                               List<TagStat> stats, List<EmotionTrendStat> trend,
                               Long userId, long sampleCount) {
        UserProfile profile = profileService.getLatest(userId);
        String prompt = promptUtil.render("report-generation.txt", Map.of(
                "reportType", reportType,
                "period", startDate + " 至 " + endDate,
                "tagStats", renderTagStats(stats),
                "emotionTrend", renderTrend(trend),
                "profileSummary", profile == null ? "（暂无画像数据）"
                        : "样本量 " + profile.getSampleCount() + "，情绪特征 " + profile.getEmotionFeature()));
        try {
            String sections = llmGateway.call(LlmRequest.of(AgentRole.PROFILE_REPORT, prompt));
            return sections == null ? "" : sections;
        } catch (Exception e) {
            log.error("报告结论生成失败，仅输出统计部分: type={}", reportType, e);
            return "（本节生成失败，仅呈现客观统计数据）";
        }
    }

    private ReportModel buildModel(String reportType, LocalDate startDate, LocalDate endDate,
                                   List<TagStat> stats, List<EmotionTrendStat> trend,
                                   long sampleCount, String llmSections) {
        String title = switch (reportType) {
            case AnalysisReport.TYPE_DAILY -> "情绪日报";
            case AnalysisReport.TYPE_WEEKLY -> "情绪周报";
            case AnalysisReport.TYPE_MONTHLY -> "情绪月报";
            default -> "情绪分析报告";
        };
        String summary = sampleCount < MIN_SAMPLE_COUNT
                ? "数据有限，结论仅供参考。本期共 " + sampleCount + " 条对话样本。"
                : "本期共 " + sampleCount + " 条对话样本，主导情绪：" + dominantEmotions(stats);
        List<Section> sections = new ArrayList<>();
        sections.add(new Section("标签统计", tagTableLines(stats, sampleCount)));
        sections.add(new Section("情绪走势", trendLines(trend)));
        sections.add(new Section("模型分析", List.of(llmSections.split("\n"))));
        return new ReportModel(title + "（" + startDate + " ~ " + endDate + "）",
                startDate + " 至 " + endDate, summary, dedupSections(sections));
    }

    private List<Section> dedupSections(List<Section> sections) {
        Map<String, List<String>> merged = new LinkedHashMap<>();
        for (Section section : sections) {
            merged.merge(section.heading(), section.lines(), (a, b) -> {
                List<String> all = new ArrayList<>(a);
                all.addAll(b);
                return all;
            });
        }
        return merged.entrySet().stream()
                .map(e -> new Section(e.getKey(), e.getValue()))
                .toList();
    }

    private AnalysisReport assembleReport(Long userId, String reportType, LocalDate startDate,
                                          LocalDate endDate, String content, List<TagStat> stats,
                                          List<EmotionTrendStat> trend, long sampleCount, Long taskId) {
        AnalysisReport report = new AnalysisReport();
        report.setUserId(userId);
        report.setReportType(reportType);
        report.setReportTitle("自我镜像" + reportType + "报告 " + startDate + "~" + endDate);
        report.setStartDate(startDate);
        report.setEndDate(endDate);
        report.setSummary(content.length() > 200 ? content.substring(0, 200) : content);
        report.setContent(content);
        report.setStatData(JsonUtil.toJson(Map.of(
                "sampleCount", sampleCount,
                "tagStats", stats,
                "emotionTrend", trend)));
        report.setTaskId(taskId);
        return report;
    }

    private List<String> tagTableLines(List<TagStat> stats, long sampleCount) {
        long total = stats.stream().mapToLong(TagStat::getCnt).sum();
        List<String> lines = new ArrayList<>(List.of("| 标签 | 次数 | 占比 |", "| --- | --- | --- |"));
        stats.stream().limit(15).forEach(stat ->
                lines.add(reportRenderer.tagTableRow(stat.getTagName(), stat.getCnt(), total)));
        lines.add("");
        lines.add("> 数据来源：tag_record（消息级标签，可溯源至原始对话），正式标签样本 " + sampleCount + " 条。");
        return lines;
    }

    private List<String> trendLines(List<EmotionTrendStat> trend) {
        if (trend.isEmpty()) {
            return List.of("本期无足够情绪标签数据。");
        }
        List<String> lines = new ArrayList<>(List.of("| 日期 | 主导情绪 | 次数 |", "| --- | --- | --- |"));
        trend.forEach(item -> lines.add(
                "| " + item.getStatDay() + " | " + item.getTagName() + " | " + item.getCnt() + " |"));
        return lines;
    }

    private String renderTagStats(List<TagStat> stats) {
        if (stats.isEmpty()) {
            return "（无正式标签数据）";
        }
        long total = stats.stream().mapToLong(TagStat::getCnt).sum();
        StringBuilder builder = new StringBuilder();
        stats.forEach(stat -> builder.append(stat.getTagCategory()).append('/').append(stat.getTagName())
                .append('/').append(stat.getCnt()).append('/').append(total == 0 ? "-"
                        : String.format("%.1f%%", stat.getCnt() * 100.0 / total)).append('\n'));
        return builder.toString();
    }

    private String renderTrend(List<EmotionTrendStat> trend) {
        if (trend.isEmpty()) {
            return "（无情绪走势数据）";
        }
        StringBuilder builder = new StringBuilder();
        trend.forEach(item -> builder.append(item.getStatDay()).append('/').append(item.getTagName())
                .append('/').append(item.getCnt()).append('\n'));
        return builder.toString();
    }

    private String dominantEmotions(List<TagStat> stats) {
        return stats.stream()
                .filter(s -> "emotion".equals(s.getTagCategory()))
                .limit(3)
                .map(TagStat::getTagName)
                .reduce((a, b) -> a + "、" + b)
                .orElse("（无）");
    }

    private Map<String, Object> featureOf(List<TagStat> stats, String category) {
        List<Map<String, Object>> top = stats.stream()
                .filter(s -> category.equals(s.getTagCategory()))
                .limit(5)
                .map(s -> Map.<String, Object>of("code", s.getTagCode(),
                        "name", s.getTagName(), "count", s.getCnt()))
                .toList();
        return Map.of("topTags", top, "minConfidence", agentProperties.getLowConfidenceThreshold());
    }

    private Map<String, Object> behaviorFeature(Long userId, LocalDateTime start, LocalDateTime end) {
        List<MessageMapper.HourStat> hours = messageMapper.statActiveHours(userId, start, end);
        return Map.of("activeHours", hours.stream()
                .map(h -> Map.of("hour", h.getHourOfDay(), "count", h.getCnt())).toList());
    }
}
