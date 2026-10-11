package com.mirror.agent.config;

import com.mirror.agent.agent.resident.TaskExecutionAgent;
import com.mirror.agent.dp.scheduler.TaskTriggerJob;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.quartz.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Quartz 配置：将任务执行 Agent 的 ObjectProvider 放入 SchedulerContext，
 * 供 {@link TaskTriggerJob} 在触发时获取（延迟解析，避免循环依赖）。
 */
@Configuration
public class QuartzConfig {

    @Bean
    public SchedulerFactoryBeanCustomizer taskHandlerCustomizer(
            ObjectProvider<TaskExecutionAgent> taskExecutionAgentProvider) {
        Map<String, Object> schedulerContext = new HashMap<>();
        schedulerContext.put(TaskTriggerJob.HANDLER_PROVIDER_KEY, taskExecutionAgentProvider);
        return factory -> factory.setSchedulerContextAsMap(schedulerContext);
    }
}
