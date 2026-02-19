package com.starfield.api.service;

import com.starfield.api.dto.DownloadResponse;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.repository.TranslationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * 下载服务，返回 COS 下载链接
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    final TranslationTaskRepository translationTaskRepository;

    /**
     * 获取下载链接和文件名
     *
     * @param taskId 任务 ID
     * @return 下载响应，包含 COS URL 和文件名
     */
    public DownloadResponse getDownloadFile(String taskId) {
        log.info("[getDownloadFile] 下载请求 taskId {}", taskId);

        var task = translationTaskRepository.selectById(taskId);
        if (Objects.isNull(task)) {
            log.warn("[getDownloadFile] 任务不存在 taskId {}", taskId);
            throw new TaskService.TaskNotFoundException(taskId);
        }

        if (task.getStatus() != TaskStatus.completed) {
            if (task.getStatus() == TaskStatus.expired) {
                log.warn("[getDownloadFile] 任务已过期 taskId {}", taskId);
                throw new TaskExpiredException(taskId);
            }
            log.warn("[getDownloadFile] 任务未完成 taskId {} status {}", taskId, task.getStatus());
            throw new TaskNotCompletedException(taskId);
        }

        var downloadUrl = task.getDownloadUrl();
        if (Objects.isNull(downloadUrl)) {
            log.error("[getDownloadFile] 下载链接为空 taskId {}", taskId);
            throw new DownloadUrlEmptyException(taskId);
        }

        var fileName = resolveFileName(task.getFileName());
        log.info("[getDownloadFile] 返回下载链接 taskId {} fileName {}", taskId, fileName);
        return new DownloadResponse(downloadUrl, fileName);
    }

    /**
     * 根据原始文件名生成 zip 文件名
     *
     * @param originalFileName 原始文件名
     * @return zip 文件名
     */
    private String resolveFileName(String originalFileName) {
        if (Objects.isNull(originalFileName)) {
            return "download.zip";
        }
        var baseName = originalFileName.contains(".")
                ? originalFileName.substring(0, originalFileName.lastIndexOf('.'))
                : originalFileName;
        return baseName + ".zip";
    }

    /** 任务未完成异常 */
    public static class TaskNotCompletedException extends RuntimeException {
        public TaskNotCompletedException(String taskId) {
            super("翻译任务尚未完成 taskId " + taskId);
        }
    }

    /** 任务已过期异常 */
    public static class TaskExpiredException extends RuntimeException {
        public TaskExpiredException(String taskId) {
            super("翻译任务已过期 文件已清理 taskId " + taskId);
        }
    }

    /** 下载链接为空异常 */
    public static class DownloadUrlEmptyException extends RuntimeException {
        public DownloadUrlEmptyException(String taskId) {
            super("下载链接为空 taskId " + taskId);
        }
    }
}
