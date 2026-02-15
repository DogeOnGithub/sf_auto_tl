package com.starfield.api.service;

import com.starfield.api.client.EngineClient;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.CreationRepository;
import com.starfield.api.repository.CreationVersionRepository;
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
    EngineClient engineClient;

    @Mock
    CosService cosService;

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
    void getTask_engineReturnsOutputPath_updatesTask(@TempDir Path tempDir) throws IOException {
        var outputFile = tempDir.resolve("mod_zh-CN.esm");
        Files.writeString(outputFile, "translated content");

        var task = createTask("task-7", "mod.esm", TaskStatus.assembling, 10, 10);
        when(translationTaskRepository.selectById("task-7")).thenReturn(task);
        when(engineClient.getTaskStatus("task-7")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-7", "completed",
                        new EngineClient.EngineProgress(10, 10), outputFile.toString(), null, null)
        );
        when(cosService.uploadFile(any(Path.class), any(String.class), eq("mod.zip")))
                .thenReturn("https://cos.example.com/translations/task-7/mod.zip");

        taskService.getTask("task-7");

        assertThat(task.getOutputFilePath()).isEqualTo(outputFile.toString());
        assertThat(task.getDownloadUrl()).isEqualTo("https://cos.example.com/translations/task-7/mod.zip");
        verify(translationTaskRepository).updateById(task);
    }

    /** 任务完成时输出文件不存在应标记为失败 */
    @Test
    void getTask_completedButOutputMissing_marksAsFailed() {
        var task = createTask("task-8", "mod.esm", TaskStatus.assembling, 10, 10);
        when(translationTaskRepository.selectById("task-8")).thenReturn(task);
        when(engineClient.getTaskStatus("task-8")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-8", "completed",
                        new EngineClient.EngineProgress(10, 10), "/nonexistent/output.esm", null, null)
        );

        var response = taskService.getTask("task-8");

        assertThat(response.status()).isEqualTo("failed");
        assertThat(task.getErrorMessage()).isEqualTo("翻译输出文件不存在");
    }

    /** 任务完成且 download_url 已存在时不应重复上传 */
    @Test
    void getTask_completedWithExistingDownloadUrl_skipsUpload() {
        var task = createTask("task-9", "mod.esm", TaskStatus.assembling, 10, 10);
        task.setDownloadUrl("https://cos.example.com/existing.zip");
        when(translationTaskRepository.selectById("task-9")).thenReturn(task);
        when(engineClient.getTaskStatus("task-9")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-9", "completed",
                        new EngineClient.EngineProgress(10, 10), "/output/mod.esm", null, null)
        );

        taskService.getTask("task-9");

        verify(cosService, never()).uploadFile(any(), any(), any());
    }

    /** 任务失败时应清理本地文件 */
    @Test
    void getTask_engineReturnsFailed_cleansUpFiles(@TempDir Path tempDir) throws IOException {
        var filePath = tempDir.resolve("upload.esm");
        var outputPath = tempDir.resolve("output.esm");
        Files.writeString(filePath, "original");
        Files.writeString(outputPath, "translated");

        var task = createTask("task-10", "mod.esm", TaskStatus.translating, 5, 10);
        task.setFilePath(filePath.toString());
        task.setOutputFilePath(outputPath.toString());
        when(translationTaskRepository.selectById("task-10")).thenReturn(task);
        when(engineClient.getTaskStatus("task-10")).thenReturn(
                new EngineClient.EngineTaskStatusResponse("task-10", "failed",
                        new EngineClient.EngineProgress(5, 10), null, null, "translation error")
        );

        taskService.getTask("task-10");

        assertThat(Files.exists(filePath)).isFalse();
        assertThat(Files.exists(outputPath)).isFalse();
    }

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
