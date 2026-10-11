package com.mirror.agent.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mirror.agent.common.BizException;
import com.mirror.agent.common.ErrorCode;
import com.mirror.agent.dp.entity.TaskState;
import com.mirror.agent.entity.SubTask;
import com.mirror.agent.entity.Task;
import com.mirror.agent.repository.SubTaskMapper;
import com.mirror.agent.repository.TaskMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务持久化与状态机流转服务（docs/agent_implementation.md §3.5）。
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskMapper taskMapper;
    private final SubTaskMapper subTaskMapper;

    public Task create(Task task) {
        task.setStatus(TaskState.PENDING.getCode());
        task.setProgress(0);
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.insert(task);
        return task;
    }

    public Task require(Long taskId, Long userId) {
        Task task = taskMapper.selectOne(Wrappers.<Task>lambdaQuery()
                .eq(Task::getId, taskId)
                .eq(Task::getUserId, userId));
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
    }

    public Task getById(Long taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
    }

    /** 状态机校验后的状态流转，非法流转抛出业务异常 */
    public void updateState(Long taskId, TaskState target, String errorMsg) {
        Task task = getById(taskId);
        TaskState current = TaskState.fromCode(task.getStatus());
        current.transferTo(target);
        task.setStatus(target.getCode());
        task.setErrorMsg(errorMsg);
        if (target.isFinished() && target != TaskState.FAILED) {
            task.setProgress(target == TaskState.COMPLETED ? 100 : task.getProgress());
        }
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    public void updateProgress(Long taskId, int progress) {
        Task task = getById(taskId);
        task.setProgress(progress);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    public void bindResult(Long taskId, Long resultId) {
        Task task = getById(taskId);
        task.setResultId(resultId);
        task.setUpdateTime(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    /** 按步骤名生成子任务列表（任务拆解） */
    @Transactional(rollbackFor = Exception.class)
    public List<SubTask> splitSteps(Long taskId, List<String> stepNames) {
        List<SubTask> subTasks = stepNames.stream().map(name -> {
            SubTask subTask = new SubTask();
            subTask.setTaskId(taskId);
            subTask.setStepName(name);
            subTask.setRetryCount(0);
            return subTask;
        }).toList();
        for (int i = 0; i < subTasks.size(); i++) {
            SubTask subTask = subTasks.get(i);
            subTask.setStepOrder(i + 1);
            subTask.setStatus(TaskState.PENDING.getCode());
            subTaskMapper.insert(subTask);
        }
        return subTasks;
    }

    public List<SubTask> listSubTasks(Long taskId) {
        return subTaskMapper.selectList(Wrappers.<SubTask>lambdaQuery()
                .eq(SubTask::getTaskId, taskId)
                .orderByAsc(SubTask::getStepOrder));
    }

    public void updateSubTaskState(SubTask subTask, TaskState state) {
        subTask.setStatus(state.getCode());
        subTask.setUpdateTime(LocalDateTime.now());
        subTaskMapper.updateById(subTask);
    }

    public List<Task> listByUser(Long userId) {
        return taskMapper.selectList(Wrappers.<Task>lambdaQuery()
                .eq(Task::getUserId, userId)
                .orderByDesc(Task::getCreateTime));
    }
}
