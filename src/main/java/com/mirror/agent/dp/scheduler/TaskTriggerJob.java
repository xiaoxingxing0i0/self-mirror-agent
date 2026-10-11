package com.mirror.agent.dp.scheduler;

import com.mirror.agent.agent.resident.TaskExecutionAgent;
import lombok.extern.slf4j.Slf4j;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Quartz 周期任务触发器：从 SchedulerContext 获取任务执行 Agent
 * （Quartz 要求 Job 无参构造，依赖通过调度器上下文注入，规避字段 @Autowired）。
 */
@Slf4j
public class TaskTriggerJob implements Job {

    /** SchedulerContext 中 ObjectProvider<TaskExecutionAgent> 的键 */
    public static final String HANDLER_PROVIDER_KEY = "mirrorTaskExecutionAgentProvider";

    @Override
    @SuppressWarnings("unchecked")
    public void execute(JobExecutionContext context) throws JobExecutionException {
        String taskId = context.getJobDetail().getJobDataMap().getString("taskId");
        Object provider = null;
        try {
            provider = context.getScheduler().getContext().get(HANDLER_PROVIDER_KEY);
            if (provider == null || taskId == null) {
                throw new IllegalStateException("任务处理器或 taskId 缺失");
            }
            ((ObjectProvider<TaskExecutionAgent>) provider).getObject().executeTask(Long.valueOf(taskId));
        } catch (Exception e) {
            log.error("周期任务触发失败: taskId={}", taskId, e);
            throw new JobExecutionException(e, false);
        }
    }
}
