package com.mirror.agent.controller;

import com.mirror.agent.common.Result;
import com.mirror.agent.entity.Task;
import com.mirror.agent.service.TaskFacadeService;
import com.mirror.agent.util.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务接口：任务列表（含进度）/ 取消 / 重试。
 * 任务创建通常由对话链路中的任务执行 Agent 触发，此处提供管理操作。
 */
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskFacadeService taskFacadeService;

    @GetMapping
    public Result<List<Task>> list() {
        return Result.ok(taskFacadeService.listByUser(UserContext.getUserId()));
    }

    @PostMapping("/{taskId}/cancel")
    public Result<Void> cancel(@PathVariable Long taskId) {
        taskFacadeService.cancel(taskId, UserContext.getUserId());
        return Result.ok();
    }

    @PostMapping("/{taskId}/retry")
    public Result<Void> retry(@PathVariable Long taskId) {
        taskFacadeService.retry(taskId, UserContext.getUserId());
        return Result.ok();
    }
}
