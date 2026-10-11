package com.mirror.agent.service;

import com.mirror.agent.agent.resident.TaskExecutionAgent;
import com.mirror.agent.entity.Task;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 任务门面服务：接口层仅依赖 service 层（硬性规则 1 依赖方向），
 * 由本门面转发到任务执行 Agent。
 */
@Service
@RequiredArgsConstructor
public class TaskFacadeService {

    private final TaskService taskService;
    private final TaskExecutionAgent taskExecutionAgent;

    public List<Task> listByUser(Long userId) {
        return taskService.listByUser(userId);
    }

    public void cancel(Long taskId, Long userId) {
        taskExecutionAgent.cancelTask(taskId, userId);
    }

    public void retry(Long taskId, Long userId) {
        taskExecutionAgent.retryTask(taskId, userId);
    }
}
