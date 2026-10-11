package com.mirror.agent.agent.resident;

import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.config.AgentProperties;
import com.mirror.agent.dp.entity.TaskState;
import com.mirror.agent.entity.AnalysisReport;
import com.mirror.agent.entity.SubTask;
import com.mirror.agent.entity.Task;
import com.mirror.agent.entity.UserProfile;
import com.mirror.agent.message.AgentResult;
import com.mirror.agent.service.TaskService;
import com.mirror.agent.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 任务执行 Agent（docs/agent_definition.md §2.5）。
 * 全生命周期管理：创建 → 拆解 → 执行（子任务重试）→ 状态同步；
 * 周期任务通过 Quartz Cron 触发；任务 ID + 状态机保证幂等与非法流转拦截。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskExecutionAgent {

    /** 报告类任务的标准拆解步骤（docs/agent_implementation.md §3.5） */
    private static final List<String> REPORT_STEPS =
            List.of("拉取历史数据", "指标统计", "生成报告", "合规校验");
    private static final List<String> PROFILE_STEPS = List.of("拉取标签数据", "聚合画像");
    private static final List<String> STATISTIC_STEPS = List.of("拉取历史数据", "指标统计");

    private final TaskService taskService;
    private final ProfileReportAgent profileReportAgent;
    private final Scheduler scheduler;
    private final TaskExecutor taskExecutor;
    private final AgentProperties agentProperties;

    /** 报告任务参数 */
    public record ReportParams(String reportType, String startDate, String endDate) {
    }

    /** 创建任务 → 拆解子任务 → 周期任务注册 Quartz / 即时任务异步执行 */
    public AgentResult<Task> createTask(Long userId, String taskType, String taskName,
                                        Map<String, Object> params, String cron) {
        Task task = new Task();
        task.setUserId(userId);
        task.setTaskType(taskType);
        task.setTaskName(taskName);
        task.setTaskParams(JsonUtil.toJson(params));
        task.setCronExpression(cron);
        taskService.create(task);
        taskService.splitSteps(task.getId(), splitRule(taskType));
        if (cron != null && !cron.isBlank()) {
            scheduleCron(task);
        } else {
            taskExecutor.execute(() -> executeTask(task.getId()));
        }
        return AgentResult.ok(task);
    }

    /** 执行任务（Quartz 触发 / 即时任务共用入口，幂等：终态任务直接跳过） */
    public void executeTask(Long taskId) {
        Task task = taskService.getById(taskId);
        if (TaskState.fromCode(task.getStatus()).isFinished()) {
            log.info("任务已处于终态，跳过执行: taskId={}, status={}", taskId, task.getStatus());
            return;
        }
        taskService.updateState(taskId, TaskState.RUNNING, null);
        List<SubTask> steps = taskService.listSubTasks(taskId);
        try {
            for (SubTask step : steps) {
                executeStepWithRetry(task, step);
                taskService.updateProgress(taskId, step.getStepOrder() * 100 / steps.size());
            }
            taskService.updateState(taskId, TaskState.COMPLETED, null);
            log.info("任务执行完成: taskId={}", taskId);
        } catch (Exception e) {
            log.error("任务执行失败: taskId={}", taskId, e);
            String errorMsg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            taskService.updateState(taskId, TaskState.FAILED,
                    errorMsg.length() > 500 ? errorMsg.substring(0, 500) : errorMsg);
        }
    }

    /** 子任务失败默认重试 N 次，超限标记失败并终止整个任务 */
    private void executeStepWithRetry(Task task, SubTask step) {
        taskService.updateSubTaskState(step, TaskState.RUNNING);
        int maxRetry = agentProperties.getSubTaskMaxRetry();
        for (int attempt = 0; ; attempt++) {
            try {
                runStepLogic(task, step);
                taskService.updateSubTaskState(step, TaskState.COMPLETED);
                return;
            } catch (Exception e) {
                step.setRetryCount(attempt + 1);
                if (attempt >= maxRetry) {
                    taskService.updateSubTaskState(step, TaskState.FAILED);
                    throw e;
                }
                log.warn("子任务失败重试: taskId={}, step={}, attempt={}",
                        task.getId(), step.getStepName(), attempt + 1);
            }
        }
    }

    private void runStepLogic(Task task, SubTask step) {
        switch (step.getStepName()) {
            case "生成报告" -> generateReportStep(task);
            case "聚合画像" -> buildProfileStep(task);
            default -> log.debug("步骤「{}」的数据准备/校验已内聚于报告生成流程: taskId={}",
                    step.getStepName(), task.getId());
        }
    }

    private void generateReportStep(Task task) {
        ReportParams params = resolveParams(task);
        AgentResult<AnalysisReport> result = profileReportAgent.generateReport(task.getUserId(),
                params.reportType(),
                LocalDate.parse(params.startDate()),
                LocalDate.parse(params.endDate()),
                task.getId());
        if (!result.isSuccess() || result.getData() == null) {
            throw new BizException(ErrorCode.REPORT_GENERATE_FAILED, result.getErrorMessage());
        }
        taskService.bindResult(task.getId(), result.getData().getId());
    }

    private void buildProfileStep(Task task) {
        AgentResult<UserProfile> result =
                profileReportAgent.buildProfile(task.getUserId(), LocalDate.now());
        if (!result.isSuccess()) {
            log.warn("画像聚合无新样本: taskId={}, msg={}", task.getId(), result.getErrorMessage());
        }
    }

    private ReportParams resolveParams(Task task) {
        ReportParams params = JsonUtil.fromJson(task.getTaskParams(), ReportParams.class);
        if (params == null || params.startDate() == null || params.endDate() == null) {
            LocalDate end = LocalDate.now();
            params = new ReportParams(params == null ? AnalysisReport.TYPE_WEEKLY : params.reportType(),
                    end.minusDays(6).toString(), end.toString());
        }
        return params;
    }

    public AgentResult<Void> cancelTask(Long taskId, Long userId) {
        Task task = taskService.require(taskId, userId);
        taskService.updateState(taskId, TaskState.CANCELLED, null);
        unschedule(taskId);
        return AgentResult.ok(null);
    }

    public AgentResult<Void> retryTask(Long taskId, Long userId) {
        taskService.require(taskId, userId);
        taskService.updateState(taskId, TaskState.PENDING, null);
        taskExecutor.execute(() -> executeTask(taskId));
        return AgentResult.ok(null);
    }

    private void scheduleCron(Task task) {
        try {
            JobDetail jobDetail = JobBuilder.newJob(com.mirror.agent.dp.scheduler.TaskTriggerJob.class)
                    .withIdentity(jobKey(task.getId()))
                    .usingJobData("taskId", String.valueOf(task.getId()))
                    .storeDurably()
                    .build();
            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity(new TriggerKey("trigger-" + task.getId(), "mirror-task"))
                    .withSchedule(CronScheduleBuilder.cronSchedule(task.getCronExpression()))
                    .build();
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("周期任务已注册: taskId={}, cron={}", task.getId(), task.getCronExpression());
        } catch (SchedulerException e) {
            throw new BizException(ErrorCode.TASK_STATE_ILLEGAL, "Cron 任务注册失败: " + e.getMessage(), e);
        }
    }

    private void unschedule(Long taskId) {
        try {
            scheduler.deleteJob(jobKey(taskId));
        } catch (SchedulerException e) {
            log.warn("取消周期任务失败: taskId={}", taskId, e);
        }
    }

    private JobKey jobKey(Long taskId) {
        return new JobKey("job-" + taskId, "mirror-task");
    }

    private List<String> splitRule(String taskType) {
        return switch (taskType) {
            case Task.TYPE_REPORT -> REPORT_STEPS;
            case Task.TYPE_PROFILE_UPDATE -> PROFILE_STEPS;
            default -> STATISTIC_STEPS;
        };
    }
}
