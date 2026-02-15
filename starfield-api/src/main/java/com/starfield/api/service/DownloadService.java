package com.starfield.api.service;

import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.TranslationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 下载服务，处理翻译文件和原始文件的下载逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DownloadService {

    final TranslationTaskRepository translationTaskRepository;

    /**
     * 获取下载文件资源和文件名
     *
     * @param taskId 任务 ID
     * @param type   下载类型 translated 或 original
     * @return 下载结果，包含文件资源和文件名
     */
    public DownloadResult getDownloadFile(String taskId, String type) {
        log.info("[getDownloadFile] 下载请求 taskId {} type {}", taskId, type);

        var task = translationTaskRepository.selectById(taskId);
        if (Objects.isNull(task)) {
            log.warn("[getDownloadFile] 任务不存在 taskId {}", taskId);
            throw new TaskService.TaskNotFoundException(taskId);
        }

        if (task.getStatus() != TaskStatus.completed) {
            log.warn("[getDownloadFile] 任务未完成 taskId {} status {}", taskId, task.getStatus());
            throw new TaskNotCompletedException(taskId);
        }

        var filePath = resolveFilePath(task, type);
        var fileName = resolveFileName(task, type);
        var resource = new FileSystemResource(Path.of(filePath));

        if (!resource.exists()) {
            log.error("[getDownloadFile] 文件不存在 taskId {} path {}", taskId, filePath);
            throw new FileNotFoundException(taskId, filePath);
        }

        log.info("[getDownloadFile] 返回文件 taskId {} fileName {}", taskId, fileName);
        return new DownloadResult(resource, fileName);
    }

    /** 根据下载类型解析文件路径 */
    private String resolveFilePath(TranslationTask task, String type) {
        if ("original".equals(type)) {
            var path = task.getOriginalBackupPath();
            if (Objects.isNull(path)) {
                // 没有备份路径时回退到上传文件路径
                path = task.getFilePath();
            }
            if (Objects.isNull(path)) {
                throw new FileNotFoundException(task.getTaskId(), "原始文件路径为空");
            }
            return path;
        }
        var outputPath = task.getOutputFilePath();
        if (Objects.isNull(outputPath)) {
            throw new FileNotFoundException(task.getTaskId(), "翻译输出文件路径为空");
        }
        return outputPath;
    }

    /** 根据下载类型生成下载文件名 */
    private String resolveFileName(TranslationTask task, String type) {
        var originalName = task.getFileName();
        if ("original".equals(type)) {
            return originalName;
        }
        var baseName = originalName.contains(".")
                ? originalName.substring(0, originalName.lastIndexOf('.'))
                : originalName;
        return baseName + "_" + task.getTargetLang() + ".esm";
    }

    /** 下载结果 */
    public record DownloadResult(Resource resource, String fileName) {}

    /** 任务未完成异常 */
    public static class TaskNotCompletedException extends RuntimeException {
        public TaskNotCompletedException(String taskId) {
            super("翻译任务尚未完成 taskId " + taskId);
        }
    }

    /** 文件不存在异常 */
    public static class FileNotFoundException extends RuntimeException {
        public FileNotFoundException(String taskId, String path) {
            super("文件不存在 taskId " + taskId + " path " + path);
        }
    }
}
