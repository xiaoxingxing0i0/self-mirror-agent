package com.mirror.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.entity.AnalysisReport;
import com.mirror.agent.repository.AnalysisReportMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 分析报告持久化与查询服务。
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    private final AnalysisReportMapper reportMapper;

    public AnalysisReport save(AnalysisReport report) {
        report.setCreateTime(LocalDateTime.now());
        reportMapper.insert(report);
        return report;
    }

    public AnalysisReport require(Long reportId, Long userId) {
        AnalysisReport report = reportMapper.selectOne(Wrappers.<AnalysisReport>lambdaQuery()
                .eq(AnalysisReport::getId, reportId)
                .eq(AnalysisReport::getUserId, userId));
        if (report == null) {
            throw new BizException(ErrorCode.REPORT_NOT_FOUND);
        }
        return report;
    }

    public List<AnalysisReport> listByUser(Long userId) {
        return reportMapper.selectList(Wrappers.<AnalysisReport>lambdaQuery()
                .eq(AnalysisReport::getUserId, userId)
                .orderByDesc(AnalysisReport::getCreateTime));
    }
}
