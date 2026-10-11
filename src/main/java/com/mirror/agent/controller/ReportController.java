package com.mirror.agent.controller;

import com.mirror.agent.common.Result;
import com.mirror.agent.entity.AnalysisReport;
import com.mirror.agent.service.ReportService;
import com.mirror.agent.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 报告接口：报告列表 / 报告详情（Markdown 全文 + JSON 统计指标）。
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reportService;

    @GetMapping
    public Result<List<AnalysisReport>> list() {
        return Result.ok(reportService.listByUser(UserContext.getUserId()));
    }

    @GetMapping("/{reportId}")
    public Result<AnalysisReport> detail(@PathVariable Long reportId) {
        return Result.ok(reportService.require(reportId, UserContext.getUserId()));
    }
}
