package com.mirror.agent.dp.entity;

import com.mirror.agent.common.BizException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 任务状态机单元测试：合法流转放行、非法流转拦截。
 */
class TaskStateTest {

    @Test
    void legalTransitionsPass() {
        assertEquals(TaskState.RUNNING, TaskState.PENDING.transferTo(TaskState.RUNNING));
        assertEquals(TaskState.COMPLETED, TaskState.RUNNING.transferTo(TaskState.COMPLETED));
        assertEquals(TaskState.CANCELLED, TaskState.RUNNING.transferTo(TaskState.CANCELLED));
        assertEquals(TaskState.RUNNING, TaskState.FAILED.transferTo(TaskState.RUNNING));
    }

    @Test
    void illegalTransitionsThrow() {
        assertThrows(BizException.class, () -> TaskState.PENDING.transferTo(TaskState.COMPLETED));
        assertThrows(BizException.class, () -> TaskState.COMPLETED.transferTo(TaskState.RUNNING));
        assertThrows(BizException.class, () -> TaskState.CANCELLED.transferTo(TaskState.PENDING));
    }

    @Test
    void finishedStatesDetected() {
        assertTrue(TaskState.COMPLETED.isFinished());
        assertTrue(TaskState.FAILED.isFinished());
        assertTrue(TaskState.CANCELLED.isFinished());
        assertTrue(!TaskState.RUNNING.isFinished());
    }

    @Test
    void codeMappingMatchesDataModel() {
        assertEquals(0, TaskState.PENDING.getCode());
        assertEquals(1, TaskState.RUNNING.getCode());
        assertEquals(2, TaskState.COMPLETED.getCode());
        assertEquals(3, TaskState.FAILED.getCode());
        assertEquals(4, TaskState.CANCELLED.getCode());
        assertEquals(TaskState.RUNNING, TaskState.fromCode(1));
    }
}
