package com.starfield.api.service;

import com.starfield.api.client.EngineClient;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.CreationRepository;
import com.starfield.api.repository.CreationVersionRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TaskServiceTest {

    @Mock
    TranslationTaskRepository translationTaskRepository;

    @Mock
    CreationVersionRepository creationVersionRepository;

    @Mock
    CreationRepository creationRepository;

    @Mock
    EngineClient engineClient;

    @InjectMocks
    TaskService taskService;

    /** 查询存在的任务应返回正确的任务信息 */
    @Test
    void getTask_existingTask_returnsTaskResponse() {
        var task = createTask("task-1", "test.esm", TaskStatus.translating, 5, 10);
        when(translationTaskRepository.selectById("task-1")).thenReturn(task);
        when(engineClient.getTaskStatus("task-1")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-1", "translating",
                        new EngineClient.EngineProgress(5, 10), null, null, null)
        );

        var response = taskService.getTask("task-1");

        assertThat(response.taskId()).isEqualTo("task-1");
        assertThat(response.fileName()).isEqualTo("test.esm");
        assertThat(response.status()).isEqualTo("translating");
        assertThat(response.progress().translated()).isEqualTo(5);
        assertThat(response.progress().total()).isEqualTo(10);
    }

    /** 查询不存在的任务应抛出 TaskNotFoundException */
    @Test
    void getTask_nonExistentTask_throwsTaskNotFoundException() {
        when(translationTaskRepository.selectById("not-exist")).thenReturn(null);

        assertThatThrownBy(() -> taskService.getTask("not-exist"))
                .isInstanceOf(TaskService.TaskNotFoundException.class);
    }

    /** 引擎返回新进度时应同步更新任务 */
    @Test
    void getTask_engineReturnsNewProgress_syncsProgress() {
        var task = createTask("task-2", "mod.esm", TaskStatus.translating, 3, 10);
        when(translationTaskRepository.selectById("task-2")).thenReturn(task);
        when(engineClient.getTaskStatus("task-2")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-2", "translating",
                        new EngineClient.EngineProgress(7, 10), null, null, null)
        );

        var response = taskService.getTask("task-2");

        assertThat(response.progress().translated()).isEqualTo(7);
        verify(translationTaskRepository).updateById(any(TranslationTask.class));
    }

    /** 引擎返回状态变更时应更新任务状态 */
    @Test
    void getTask_engineReturnsStatusChange_updatesStatus() {
        var task = createTask("task-3", "mod.esm", TaskStatus.translating, 10, 10);
        when(translationTaskRepository.selectById("task-3")).thenReturn(task);
        when(engineClient.getTaskStatus("task-3")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-3", "assembling",
                        new EngineClient.EngineProgress(10, 10), null, null, null)
        );

        var response = taskService.getTask("task-3");

        assertThat(response.status()).isEqualTo("assembling");
        verify(translationTaskRepository).updateById(any(TranslationTask.class));
    }

    /** 引擎不可用时应返回数据库中的状态并累加失败计数 */
    @Test
    void getTask_engineUnavailable_returnsDbState() {
        var task = createTask("task-4", "mod.esm", TaskStatus.translating, 3, 10);
        when(translationTaskRepository.selectById("task-4")).thenReturn(task);
        when(engineClient.getTaskStatus("task-4")).thenThrow(new RuntimeException("engine down"));

        var response = taskService.getTask("task-4");

        assertThat(response.status()).isEqualTo("translating");
        assertThat(response.progress().translated()).isEqualTo(3);
        assertThat(task.getSyncFailCount()).isEqualTo(1);
        verify(translationTaskRepository).updateById(any(TranslationTask.class));
    }

    /** 已完成的任务不应调用引擎同步 */
    @Test
    void getTask_completedTask_skipsEngineSync() {
        var task = createTask("task-5", "done.esm", TaskStatus.completed, 20, 20);
        when(translationTaskRepository.selectById("task-5")).thenReturn(task);

        var response = taskService.getTask("task-5");

        assertThat(response.status()).isEqualTo("completed");
        verify(engineClient, never()).getTaskStatus(any());
    }

    /** 失败的任务不应调用引擎同步 */
    @Test
    void getTask_failedTask_skipsEngineSync() {
        var task = createTask("task-6", "fail.esm", TaskStatus.failed, 5, 10);
        when(translationTaskRepository.selectById("task-6")).thenReturn(task);

        var response = taskService.getTask("task-6");

        assertThat(response.status()).isEqualTo("failed");
        verify(engineClient, never()).getTaskStatus(any());
    }

    /** 引擎返回 outputFilePath 时应更新任务 */
    @Test
    void getTask_engineReturnsOutputPath_updatesTask() {
        var task = createTask("task-7", "mod.esm", TaskStatus.assembling, 10, 10);
        when(translationTaskRepository.selectById("task-7")).thenReturn(task);
        when(engineClient.getTaskStatus("task-7")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-7", "completed",
                        new EngineClient.EngineProgress(10, 10), "/output/mod_zh-CN.esm", null, null)
        );

        taskService.getTask("task-7");

        assertThat(task.getOutputFilePath()).isEqualTo("/output/mod_zh-CN.esm");
        verify(translationTaskRepository).updateById(task);
    }

    private TranslationTask createTask(String taskId, String fileName, TaskStatus status,
                                       int translated, int total) {
        var task = new TranslationTask();
        task.setTaskId(taskId);
        task.setFileName(fileName);
        task.setStatus(status);
        task.setTranslatedCount(translated);
        task.setTotalCount(total);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());
        return task;
    }
}
