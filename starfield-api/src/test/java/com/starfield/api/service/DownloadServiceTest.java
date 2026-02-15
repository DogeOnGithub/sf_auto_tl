package com.starfield.api.service;

import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {

    @Mock
    TranslationTaskRepository translationTaskRepository;

    @InjectMocks
    DownloadService downloadService;

    @TempDir
    Path tempDir;

    /** 下载翻译文件应返回正确的文件名格式 */
    @Test
    void getDownloadFile_translated_returnsFileWithLangSuffix() throws IOException {
        var outputFile = tempDir.resolve("output.esm");
        Files.writeString(outputFile, "translated content");

        var task = createCompletedTask("task-1", "StarfieldMod.esm",
                null, outputFile.toString(), "zh-CN");
        when(translationTaskRepository.selectById("task-1")).thenReturn(task);

        var result = downloadService.getDownloadFile("task-1", "translated");

        assertThat(result.fileName()).isEqualTo("StarfieldMod_zh-CN.esm");
        assertThat(result.resource().exists()).isTrue();
    }

    /** 下载原始文件应返回原始文件名 */
    @Test
    void getDownloadFile_original_returnsOriginalFileName() throws IOException {
        var backupFile = tempDir.resolve("backup.esm");
        Files.writeString(backupFile, "original content");

        var task = createCompletedTask("task-2", "StarfieldMod.esm",
                backupFile.toString(), "/output/out.esm", "zh-CN");
        when(translationTaskRepository.selectById("task-2")).thenReturn(task);

        var result = downloadService.getDownloadFile("task-2", "original");

        assertThat(result.fileName()).isEqualTo("StarfieldMod.esm");
        assertThat(result.resource().exists()).isTrue();
    }

    /** type 默认为 translated */
    @Test
    void getDownloadFile_defaultType_returnsTranslatedFile() throws IOException {
        var outputFile = tempDir.resolve("output.esm");
        Files.writeString(outputFile, "translated content");

        var task = createCompletedTask("task-3", "MyMod.esm",
                null, outputFile.toString(), "en-US");
        when(translationTaskRepository.selectById("task-3")).thenReturn(task);

        var result = downloadService.getDownloadFile("task-3", "translated");

        assertThat(result.fileName()).isEqualTo("MyMod_en-US.esm");
    }

    /** 任务不存在应抛出 TaskNotFoundException */
    @Test
    void getDownloadFile_taskNotFound_throwsException() {
        when(translationTaskRepository.selectById("not-exist")).thenReturn(null);

        assertThatThrownBy(() -> downloadService.getDownloadFile("not-exist", "translated"))
                .isInstanceOf(TaskService.TaskNotFoundException.class);
    }

    /** 任务未完成应抛出 TaskNotCompletedException */
    @Test
    void getDownloadFile_taskNotCompleted_throwsException() {
        var task = new TranslationTask();
        task.setTaskId("task-4");
        task.setStatus(TaskStatus.translating);
        when(translationTaskRepository.selectById("task-4")).thenReturn(task);

        assertThatThrownBy(() -> downloadService.getDownloadFile("task-4", "translated"))
                .isInstanceOf(DownloadService.TaskNotCompletedException.class);
    }

    /** waiting 状态的任务应拒绝下载 */
    @Test
    void getDownloadFile_waitingTask_throwsException() {
        var task = new TranslationTask();
        task.setTaskId("task-5");
        task.setStatus(TaskStatus.waiting);
        when(translationTaskRepository.selectById("task-5")).thenReturn(task);

        assertThatThrownBy(() -> downloadService.getDownloadFile("task-5", "translated"))
                .isInstanceOf(DownloadService.TaskNotCompletedException.class);
    }

    /** failed 状态的任务应拒绝下载 */
    @Test
    void getDownloadFile_failedTask_throwsException() {
        var task = new TranslationTask();
        task.setTaskId("task-6");
        task.setStatus(TaskStatus.failed);
        when(translationTaskRepository.selectById("task-6")).thenReturn(task);

        assertThatThrownBy(() -> downloadService.getDownloadFile("task-6", "translated"))
                .isInstanceOf(DownloadService.TaskNotCompletedException.class);
    }

    /** 文件不存在应抛出 FileNotFoundException */
    @Test
    void getDownloadFile_fileNotExists_throwsException() {
        var task = createCompletedTask("task-7", "mod.esm",
                null, "/nonexistent/path.esm", "zh-CN");
        when(translationTaskRepository.selectById("task-7")).thenReturn(task);

        assertThatThrownBy(() -> downloadService.getDownloadFile("task-7", "translated"))
                .isInstanceOf(DownloadService.FileNotFoundException.class);
    }

    /** 无扩展名的文件名应正确处理 */
    @Test
    void getDownloadFile_fileNameWithoutExtension_handlesCorrectly() throws IOException {
        var outputFile = tempDir.resolve("output.esm");
        Files.writeString(outputFile, "content");

        var task = createCompletedTask("task-8", "ModFile",
                null, outputFile.toString(), "zh-CN");
        when(translationTaskRepository.selectById("task-8")).thenReturn(task);

        var result = downloadService.getDownloadFile("task-8", "translated");

        assertThat(result.fileName()).isEqualTo("ModFile_zh-CN.esm");
    }

    private TranslationTask createCompletedTask(String taskId, String fileName,
                                                 String backupPath, String outputPath,
                                                 String targetLang) {
        var task = new TranslationTask();
        task.setTaskId(taskId);
        task.setFileName(fileName);
        task.setStatus(TaskStatus.completed);
        task.setOriginalBackupPath(backupPath);
        task.setOutputFilePath(outputPath);
        task.setTargetLang(targetLang);
        return task;
    }
}
