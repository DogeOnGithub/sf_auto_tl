package com.starfield.api.service;

import com.starfield.api.client.EngineClient;
import com.starfield.api.dto.ConfirmationSaveItem;
import com.starfield.api.dto.ProgressCallbackRequest;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.CreationRepository;
import com.starfield.api.repository.CreationVersionRepository;
import com.starfield.api.repository.CustomPromptRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    CustomPromptRepository customPromptRepository;

    @Mock
    EngineClient engineClient;

    @Mock
    CosService cosService;

    @Mock
    TranslationConfirmationService translationConfirmationService;

    @InjectMocks
    TaskService taskService;

    // ========== getTask 测试（直接返回数据库状态，不调用 Engine） ==========

    /** 查询存在的任务应返回正确的任务信息 */
    @Test
    void getTask_existingTask_returnsTaskResponse() {
        var task = createTask("task-1", "test.esm", TaskStatus.translating, 5, 10);
        when(translationTaskRepository.selectById("task-1")).thenReturn(task);

        var response = taskService.getTask("task-1");

        assertThat(response.taskId()).isEqualTo("task-1");
        assertThat(response.fileName()).isEqualTo("test.esm");
        assertThat(response.status()).isEqualTo("translating");
        assertThat(response.progress().translated()).isEqualTo(5);
        assertThat(response.progress().total()).isEqualTo(10);
        verify(engineClient, never()).getTaskStatus(any());
    }

    /** 查询不存在的任务应抛出 TaskNotFoundException */
    @Test
    void getTask_nonExistentTask_throwsTaskNotFoundException() {
        when(translationTaskRepository.selectById("not-exist")).thenReturn(null);

        assertThatThrownBy(() -> taskService.getTask("not-exist"))
                .isInstanceOf(TaskService.TaskNotFoundException.class);
    }

    /** 已完成的任务应直接返回数据库状态 */
    @Test
    void getTask_completedTask_returnsDbState() {
        var task = createTask("task-5", "done.esm", TaskStatus.completed, 20, 20);
        when(translationTaskRepository.selectById("task-5")).thenReturn(task);

        var response = taskService.getTask("task-5");

        assertThat(response.status()).isEqualTo("completed");
        verify(engineClient, never()).getTaskStatus(any());
    }

    /** 失败的任务应直接返回数据库状态 */
    @Test
    void getTask_failedTask_returnsDbState() {
        var task = createTask("task-6", "fail.esm", TaskStatus.failed, 5, 10);
        when(translationTaskRepository.selectById("task-6")).thenReturn(task);

        var response = taskService.getTask("task-6");

        assertThat(response.status()).isEqualTo("failed");
        verify(engineClient, never()).getTaskStatus(any());
    }

    // ========== syncActiveTasksFromEngine 安全网测试 ==========

    /** 安全网：引擎返回新进度时应同步更新任务 */
    @Test
    void syncActiveTasksFromEngine_engineReturnsNewProgress_syncsProgress() {
        var task = createTask("task-2", "mod.esm", TaskStatus.translating, 3, 10);
        when(translationTaskRepository.selectList(any())).thenReturn(java.util.List.of(task));
        when(engineClient.getTaskStatus("task-2")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-2", "translating",
                        new EngineClient.EngineProgress(7, 10), null, null, null)
        );

        taskService.syncActiveTasksFromEngine();

        assertThat(task.getTranslatedCount()).isEqualTo(7);
        verify(translationTaskRepository).updateById(any(TranslationTask.class));
    }

    /** 安全网：引擎返回状态变更时应更新任务状态 */
    @Test
    void syncActiveTasksFromEngine_engineReturnsStatusChange_updatesStatus() {
        var task = createTask("task-3", "mod.esm", TaskStatus.translating, 10, 10);
        when(translationTaskRepository.selectList(any())).thenReturn(java.util.List.of(task));
        when(engineClient.getTaskStatus("task-3")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-3", "assembling",
                        new EngineClient.EngineProgress(10, 10), null, null, null)
        );

        taskService.syncActiveTasksFromEngine();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.assembling);
        verify(translationTaskRepository).updateById(any(TranslationTask.class));
    }

    /** 安全网：引擎不可用时应返回数据库状态并累加失败计数 */
    @Test
    void syncActiveTasksFromEngine_engineUnavailable_incrementsFailCount() {
        var task = createTask("task-4", "mod.esm", TaskStatus.translating, 3, 10);
        when(translationTaskRepository.selectList(any())).thenReturn(java.util.List.of(task));
        when(engineClient.getTaskStatus("task-4")).thenThrow(new RuntimeException("engine down"));

        taskService.syncActiveTasksFromEngine();

        assertThat(task.getSyncFailCount()).isEqualTo(1);
        verify(translationTaskRepository).updateById(any(TranslationTask.class));
    }

    /** 安全网：引擎返回 outputFilePath 时应触发完成处理 */
    @Test
    void syncActiveTasksFromEngine_engineReturnsCompleted_handlesCompletion(@TempDir Path tempDir) throws IOException {
        var outputFile = tempDir.resolve("mod_zh-CN.esm");
        Files.writeString(outputFile, "translated content");

        var task = createTask("task-7", "mod.esm", TaskStatus.assembling, 10, 10);
        when(translationTaskRepository.selectList(any())).thenReturn(java.util.List.of(task));
        when(engineClient.getTaskStatus("task-7")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-7", "completed",
                        new EngineClient.EngineProgress(10, 10), outputFile.toString(), null, null)
        );
        when(cosService.uploadFile(any(Path.class), any(String.class), eq("mod.zip")))
                .thenReturn("https://cos.example.com/translations/task-7/mod.zip");

        taskService.syncActiveTasksFromEngine();

        assertThat(task.getOutputFilePath()).isEqualTo(outputFile.toString());
        assertThat(task.getDownloadUrl()).isEqualTo("https://cos.example.com/translations/task-7/mod.zip");
        verify(translationTaskRepository).updateById(task);
    }

    /** 安全网：任务完成时输出文件不存在应标记为失败 */
    @Test
    void syncActiveTasksFromEngine_completedButOutputMissing_marksAsFailed() {
        var task = createTask("task-8", "mod.esm", TaskStatus.assembling, 10, 10);
        when(translationTaskRepository.selectList(any())).thenReturn(java.util.List.of(task));
        when(engineClient.getTaskStatus("task-8")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-8", "completed",
                        new EngineClient.EngineProgress(10, 10), "/nonexistent/output.esm", null, null)
        );

        taskService.syncActiveTasksFromEngine();

        assertThat(task.getStatus()).isEqualTo(TaskStatus.failed);
        assertThat(task.getErrorMessage()).isEqualTo("翻译输出文件不存在");
    }

    /** 安全网：任务完成且 downloadUrl 已存在时不应重复上传 */
    @Test
    void syncActiveTasksFromEngine_completedWithExistingDownloadUrl_skipsUpload() {
        var task = createTask("task-9", "mod.esm", TaskStatus.assembling, 10, 10);
        task.setDownloadUrl("https://cos.example.com/existing.zip");
        when(translationTaskRepository.selectList(any())).thenReturn(java.util.List.of(task));
        when(engineClient.getTaskStatus("task-9")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-9", "completed",
                        new EngineClient.EngineProgress(10, 10), "/output/mod.esm", null, null)
        );

        taskService.syncActiveTasksFromEngine();

        verify(cosService, never()).uploadFile(any(), any(), any());
    }

    /** 安全网：引擎返回失败时应清理本地文件 */
    @Test
    void syncActiveTasksFromEngine_engineReturnsFailed_cleansUpFiles(@TempDir Path tempDir) throws IOException {
        var filePath = tempDir.resolve("upload.esm");
        var outputPath = tempDir.resolve("output.esm");
        Files.writeString(filePath, "original");
        Files.writeString(outputPath, "translated");

        var task = createTask("task-10", "mod.esm", TaskStatus.translating, 5, 10);
        task.setFilePath(filePath.toString());
        task.setOutputFilePath(outputPath.toString());
        when(translationTaskRepository.selectList(any())).thenReturn(java.util.List.of(task));
        when(engineClient.getTaskStatus("task-10")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-10", "failed",
                        new EngineClient.EngineProgress(5, 10), null, null, "translation error")
        );

        taskService.syncActiveTasksFromEngine();

        assertThat(Files.exists(filePath)).isFalse();
        assertThat(Files.exists(outputPath)).isFalse();
    }

    // ========== handleProgressCallback confirmation 模式测试 ==========

    /** confirmation 模式下收到 items 时应增量写入确认记录 */
    @Test
    void handleProgressCallback_confirmationModeWithItems_savesConfirmationRecords() {
        var task = createTask("task-c1", "test.esm", TaskStatus.translating, 3, 10);
        task.setConfirmationMode("confirmation");
        when(translationTaskRepository.selectById("task-c1")).thenReturn(task);

        var items = List.of(
                new ProgressCallbackRequest.TranslationItem("r1", "text", "hello", "你好"),
                new ProgressCallbackRequest.TranslationItem("r2", "text", "world", "世界")
        );
        var request = new ProgressCallbackRequest("task-c1", "translating",
                new ProgressCallbackRequest.Progress(5, 10), null, null, null, items);

        taskService.handleProgressCallback("task-c1", request);

        verify(translationConfirmationService).saveConfirmationRecords(eq("task-c1"), argThat(saveItems ->
                saveItems.size() == 2
                        && saveItems.get(0).recordId().equals("r1")
                        && saveItems.get(1).recordId().equals("r2")));
        verify(translationTaskRepository).updateById(task);
    }

    /** confirmation 模式下引擎回调 assembling 时应拦截为 pending_confirmation */
    @Test
    void handleProgressCallback_confirmationModeAssembling_interceptsToPendingConfirmation() {
        var task = createTask("task-c2", "test.esm", TaskStatus.translating, 10, 10);
        task.setConfirmationMode("confirmation");
        when(translationTaskRepository.selectById("task-c2")).thenReturn(task);

        var request = new ProgressCallbackRequest("task-c2", "assembling",
                new ProgressCallbackRequest.Progress(10, 10), null, null, null, null);

        taskService.handleProgressCallback("task-c2", request);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.pending_confirmation);
        assertThat(task.getTranslatedCount()).isEqualTo(10);
        verify(translationTaskRepository).updateById(task);
    }

    /** direct 模式下引擎回调 assembling 时应正常进入 assembling 状态 */
    @Test
    void handleProgressCallback_directModeAssembling_proceedsNormally() {
        var task = createTask("task-c3", "test.esm", TaskStatus.translating, 10, 10);
        task.setConfirmationMode("direct");
        when(translationTaskRepository.selectById("task-c3")).thenReturn(task);

        var request = new ProgressCallbackRequest("task-c3", "assembling",
                new ProgressCallbackRequest.Progress(10, 10), null, null, null, null);

        taskService.handleProgressCallback("task-c3", request);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.assembling);
        verify(translationConfirmationService, never()).saveConfirmationRecords(any(), any());
        verify(translationTaskRepository).updateById(task);
    }

    /** direct 模式下收到 items 时不应写入确认记录 */
    @Test
    void handleProgressCallback_directModeWithItems_doesNotSaveConfirmationRecords() {
        var task = createTask("task-c4", "test.esm", TaskStatus.translating, 3, 10);
        task.setConfirmationMode("direct");
        when(translationTaskRepository.selectById("task-c4")).thenReturn(task);

        var items = List.of(
                new ProgressCallbackRequest.TranslationItem("r1", "text", "hello", "你好")
        );
        var request = new ProgressCallbackRequest("task-c4", "translating",
                new ProgressCallbackRequest.Progress(5, 10), null, null, null, items);

        taskService.handleProgressCallback("task-c4", request);

        verify(translationConfirmationService, never()).saveConfirmationRecords(any(), any());
        verify(translationTaskRepository).updateById(task);
    }

    /** confirmation 模式下 assembling 回调同时携带 items 时应先写入记录再拦截状态 */
    @Test
    void handleProgressCallback_confirmationModeAssemblingWithItems_savesRecordsAndIntercepts() {
        var task = createTask("task-c5", "test.esm", TaskStatus.translating, 10, 10);
        task.setConfirmationMode("confirmation");
        when(translationTaskRepository.selectById("task-c5")).thenReturn(task);

        var items = List.of(
                new ProgressCallbackRequest.TranslationItem("r1", "text", "last", "最后")
        );
        var request = new ProgressCallbackRequest("task-c5", "assembling",
                new ProgressCallbackRequest.Progress(10, 10), null, null, null, items);

        taskService.handleProgressCallback("task-c5", request);

        verify(translationConfirmationService).saveConfirmationRecords(eq("task-c5"), any());
        assertThat(task.getStatus()).isEqualTo(TaskStatus.pending_confirmation);
        verify(translationTaskRepository).updateById(task);
    }

    // ========== createZipArchive / cleanupLocalFiles 测试 ==========

    /** createZipArchive 应将输出文件和备份文件打包到 zip */
    @Test
    void createZipArchive_withOutputAndBackup_createsZip(@TempDir Path tempDir) throws IOException {
        var outputFile = tempDir.resolve("mod_translated.esm");
        var backupFile = tempDir.resolve("mod_backup.esm");
        Files.writeString(outputFile, "translated content");
        Files.writeString(backupFile, "backup content");

        var task = createTask("task-zip", "MyMod.esm", TaskStatus.completed, 10, 10);
        task.setOutputFilePath(outputFile.toString());
        task.setOriginalBackupPath(backupFile.toString());

        var zipPath = taskService.createZipArchive(task);

        assertThat(zipPath.getFileName().toString()).isEqualTo("MyMod.zip");
        assertThat(Files.exists(zipPath)).isTrue();
        assertThat(Files.size(zipPath)).isGreaterThan(0);
    }

    /** cleanupLocalFiles 应删除所有存在的本地文件 */
    @Test
    void cleanupLocalFiles_deletesAllFiles(@TempDir Path tempDir) throws IOException {
        var filePath = tempDir.resolve("upload.esm");
        var outputPath = tempDir.resolve("output.esm");
        var backupPath = tempDir.resolve("backup.esm");
        var zipPath = tempDir.resolve("result.zip");
        Files.writeString(filePath, "a");
        Files.writeString(outputPath, "b");
        Files.writeString(backupPath, "c");
        Files.writeString(zipPath, "d");

        var task = createTask("task-clean", "mod.esm", TaskStatus.completed, 10, 10);
        task.setFilePath(filePath.toString());
        task.setOutputFilePath(outputPath.toString());
        task.setOriginalBackupPath(backupPath.toString());

        taskService.cleanupLocalFiles(task, zipPath);

        assertThat(Files.exists(filePath)).isFalse();
        assertThat(Files.exists(outputPath)).isFalse();
        assertThat(Files.exists(backupPath)).isFalse();
        assertThat(Files.exists(zipPath)).isFalse();
    }

    /** cleanupLocalFiles 对 null 路径不应抛出异常 */
    @Test
    void cleanupLocalFiles_withNullPaths_doesNotThrow() {
        var task = createTask("task-null", "mod.esm", TaskStatus.failed, 0, 0);
        // filePath, outputFilePath, originalBackupPath 均为 null

        taskService.cleanupLocalFiles(task, null);

        // 不抛出异常即为通过
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
