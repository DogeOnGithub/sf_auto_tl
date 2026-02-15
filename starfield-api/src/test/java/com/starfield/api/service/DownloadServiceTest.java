package com.starfield.api.service;

import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.TranslationTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadServiceTest {

    @Mock
    TranslationTaskRepository translationTaskRepository;

    @InjectMocks
    DownloadService downloadService;

    /** 已完成任务应返回 COS URL 和 zip 文件名 */
    @Test
    void getDownloadFile_completed_returnsDownloadResponse() {
        var task = createCompletedTask("task-1", "StarfieldMod.esm",
                "https://cos.example.com/translations/task-1/StarfieldMod.zip");
        when(translationTaskRepository.selectById("task-1")).thenReturn(task);

        var result = downloadService.getDownloadFile("task-1");

        assertThat(result.downloadUrl()).isEqualTo("https://cos.example.com/translations/task-1/StarfieldMod.zip");
        assertThat(result.fileName()).isEqualTo("StarfieldMod.zip");
    }

    /** 无扩展名的文件名应正确生成 zip 文件名 */
    @Test
    void getDownloadFile_fileNameWithoutExtension_returnsZipName() {
        var task = createCompletedTask("task-2", "ModFile",
                "https://cos.example.com/translations/task-2/ModFile.zip");
        when(translationTaskRepository.selectById("task-2")).thenReturn(task);

        var result = downloadService.getDownloadFile("task-2");

        assertThat(result.fileName()).isEqualTo("ModFile.zip");
    }

    /** 任务不存在应抛出 TaskNotFoundException */
    @Test
    void getDownloadFile_taskNotFound_throwsException() {
        when(translationTaskRepository.selectById("not-exist")).thenReturn(null);

        assertThatThrownBy(() -> downloadService.getDownloadFile("not-exist"))
                .isInstanceOf(TaskService.TaskNotFoundException.class);
    }

    /** 任务未完成应抛出 TaskNotCompletedException */
    @Test
    void getDownloadFile_taskNotCompleted_throwsException() {
        var task = new TranslationTask();
        task.setTaskId("task-3");
        task.setStatus(TaskStatus.translating);
        when(translationTaskRepository.selectById("task-3")).thenReturn(task);

        assertThatThrownBy(() -> downloadService.getDownloadFile("task-3"))
                .isInstanceOf(DownloadService.TaskNotCompletedException.class);
    }

    /** waiting 状态的任务应拒绝下载 */
    @Test
    void getDownloadFile_waitingTask_throwsException() {
        var task = new TranslationTask();
        task.setTaskId("task-4");
        task.setStatus(TaskStatus.waiting);
        when(translationTaskRepository.selectById("task-4")).thenReturn(task);

        assertThatThrownBy(() -> downloadService.getDownloadFile("task-4"))
                .isInstanceOf(DownloadService.TaskNotCompletedException.class);
    }

    /** failed 状态的任务应拒绝下载 */
    @Test
    void getDownloadFile_failedTask_throwsException() {
        var task = new TranslationTask();
        task.setTaskId("task-5");
        task.setStatus(TaskStatus.failed);
        when(translationTaskRepository.selectById("task-5")).thenReturn(task);

        assertThatThrownBy(() -> downloadService.getDownloadFile("task-5"))
                .isInstanceOf(DownloadService.TaskNotCompletedException.class);
    }

    /** 任务完成但 download_url 为空应抛出 DownloadUrlEmptyException */
    @Test
    void getDownloadFile_downloadUrlEmpty_throwsException() {
        var task = createCompletedTask("task-6", "mod.esm", null);
        when(translationTaskRepository.selectById("task-6")).thenReturn(task);

        assertThatThrownBy(() -> downloadService.getDownloadFile("task-6"))
                .isInstanceOf(DownloadService.DownloadUrlEmptyException.class);
    }

    private TranslationTask createCompletedTask(String taskId, String fileName, String downloadUrl) {
        var task = new TranslationTask();
        task.setTaskId(taskId);
        task.setFileName(fileName);
        task.setStatus(TaskStatus.completed);
        task.setDownloadUrl(downloadUrl);
        return task;
    }
}
