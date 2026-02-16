package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.starfield.api.client.EngineClient;
import com.starfield.api.dto.ProgressCallbackRequest;
import com.starfield.api.dto.TaskResponse;
import com.starfield.api.entity.Creation;
import com.starfield.api.entity.CreationVersion;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.CreationRepository;
import com.starfield.api.repository.CreationVersionRepository;
import com.starfield.api.repository.CustomPromptRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 翻译任务服务，处理任务查询和进度同步
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    final TranslationTaskRepository translationTaskRepository;
    final CreationVersionRepository creationVersionRepository;
    final CreationRepository creationRepository;
    final CustomPromptRepository customPromptRepository;
    final EngineClient engineClient;
    final CosService cosService;

    /**
     * 查询翻译任务状态和进度，尝试从引擎同步最新进度
     *
     * @param taskId 任务 ID
     * @return 任务响应 DTO
     */
    /**
     * 查询任务状态，直接返回数据库中的状态
     */
    public TaskResponse getTask(String taskId) {
        log.info("[getTask] 查询任务 taskId {}", taskId);

        var task = translationTaskRepository.selectById(taskId);
        if (Objects.isNull(task)) {
            log.warn("[getTask] 任务不存在 taskId {}", taskId);
            throw new TaskNotFoundException(taskId);
        }

        return toResponse(task);
    }

    /**
     * 查询所有翻译任务，按创建时间倒序
     *
     * @return 任务响应列表
     */
    public List<TaskResponse> listTasks() {
        log.info("[listTasks] 查询所有任务");
        var wrapper = new QueryWrapper<TranslationTask>()
                .orderByDesc("created_at");
        var tasks = translationTaskRepository.selectList(wrapper);
        return tasks.stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 查询指定 creation 版本关联的翻译任务
     *
     * @param creationId 作品 ID
     * @return 任务响应列表
     */
    public List<TaskResponse> listTasksByCreation(Long creationId) {
        log.info("[listTasksByCreation] 查询作品关联任务 creationId {}", creationId);
        // 先查出该 creation 的所有版本 ID
        var versionWrapper = new QueryWrapper<CreationVersion>()
                .eq("creation_id", creationId)
                .select("id");
        var versionIds = creationVersionRepository.selectList(versionWrapper).stream()
                .map(CreationVersion::getId)
                .collect(Collectors.toList());

        if (versionIds.isEmpty()) {
            return List.of();
        }

        var taskWrapper = new QueryWrapper<TranslationTask>()
                .in("creation_version_id", versionIds)
                .orderByDesc("created_at");
        return translationTaskRepository.selectList(taskWrapper).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * 处理 Engine 的进度回调 更新数据库中的任务状态和进度
     *
     * @param taskId  任务 ID
     * @param request 进度回调请求
     */
    public void handleProgressCallback(String taskId, ProgressCallbackRequest request) {
        log.info("[handleProgressCallback] 收到回调 taskId {} status {}", taskId, request.status());

        var task = translationTaskRepository.selectById(taskId);
        if (Objects.isNull(task)) {
            log.warn("[handleProgressCallback] 任务不存在 taskId {}", taskId);
            return;
        }

        var currentStatus = task.getStatus();
        if (currentStatus == TaskStatus.completed || currentStatus == TaskStatus.failed) {
            log.info("[handleProgressCallback] 任务已终态 跳过 taskId {} status {}", taskId, currentStatus);
            return;
        }

        var newStatus = TaskStatus.valueOf(request.status());
        if (newStatus != task.getStatus()) {
            log.info("[handleProgressCallback] 状态变更 taskId {} {} -> {}", taskId, task.getStatus(), newStatus);
            task.setStatus(newStatus);
        }

        if (Objects.nonNull(request.progress())) {
            task.setTranslatedCount(request.progress().translated());
            task.setTotalCount(request.progress().total());
        }

        if (Objects.nonNull(request.outputFilePath())) {
            task.setOutputFilePath(request.outputFilePath());
        }

        if (Objects.nonNull(request.originalBackupPath())) {
            task.setOriginalBackupPath(request.originalBackupPath());
        }

        if (Objects.nonNull(request.error())) {
            task.setErrorMessage(request.error());
        }

        // 终态处理
        if (newStatus == TaskStatus.completed && Objects.isNull(task.getDownloadUrl())) {
            handleTaskCompleted(task);
        } else if (newStatus == TaskStatus.failed) {
            handleTaskFailed(task);
        }

        // 回调成功 重置失败计数
        task.setSyncFailCount(0);
        translationTaskRepository.updateById(task);
    }


    /**
     * 转换任务实体为响应 DTO（含 creation 信息）
     */
    /**
     * 将 TranslationTask 实体转换为 TaskResponse DTO
     */
    private TaskResponse toResponse(TranslationTask task) {
        TaskResponse.CreationInfo creationInfo = null;
        if (Objects.nonNull(task.getCreationVersionId())) {
            creationInfo = buildCreationInfo(task.getCreationVersionId());
        }
        TaskResponse.PromptInfo promptInfo = null;
        if (Objects.nonNull(task.getPromptId())) {
            var prompt = customPromptRepository.selectById(task.getPromptId());
            if (Objects.nonNull(prompt)) {
                promptInfo = new TaskResponse.PromptInfo(prompt.getId(), prompt.getName());
            }
        }
        return new TaskResponse(
                task.getTaskId(),
                task.getFileName(),
                task.getStatus().name(),
                new TaskResponse.Progress(task.getTranslatedCount(), task.getTotalCount()),
                creationInfo,
                promptInfo,
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    /**
     * 根据版本 ID 构建 creation 信息
     */
    private TaskResponse.CreationInfo buildCreationInfo(Long versionId) {
        var version = creationVersionRepository.selectById(versionId);
        if (Objects.isNull(version)) return null;

        var creation = creationRepository.selectById(version.getCreationId());
        if (Objects.isNull(creation)) return null;

        return new TaskResponse.CreationInfo(
                creation.getId(),
                creation.getName(),
                creation.getTranslatedName(),
                version.getId(),
                version.getVersion()
        );
    }

    /**
     * 查询所有非终态活跃任务 逐个向 Engine 同步进度（安全网）
     */
    public void syncActiveTasksFromEngine() {
        var wrapper = new QueryWrapper<TranslationTask>()
                .notIn("status", TaskStatus.completed.name(), TaskStatus.failed.name());
        var activeTasks = translationTaskRepository.selectList(wrapper);
        log.info("[syncActiveTasksFromEngine] 活跃任务数 {}", activeTasks.size());
        activeTasks.forEach(this::syncProgressFromEngine);
    }

    private static final int MAX_SYNC_FAIL_COUNT = 10;

    /**
     * 尝试从翻译引擎同步最新进度，引擎不可用时使用数据库状态
     * 连续同步失败超过阈值时标记任务为失败
     *
     * @param task 翻译任务实体
     */
    private void syncProgressFromEngine(TranslationTask task) {
        var status = task.getStatus();
        if (status == TaskStatus.completed || status == TaskStatus.failed) {
            return;
        }

        try {
            var engineStatus = engineClient.getTaskStatus(task.getTaskId());
            if (Objects.isNull(engineStatus)) {
                incrementSyncFailCount(task);
                return;
            }

            var newStatus = TaskStatus.valueOf(engineStatus.status());
            if (newStatus != task.getStatus()) {
                log.info("[syncProgressFromEngine] 状态变更 taskId {} oldStatus {} newStatus {}",
                        task.getTaskId(), task.getStatus(), newStatus);
                task.setStatus(newStatus);
            }

            if (Objects.nonNull(engineStatus.progress())) {
                task.setTranslatedCount(engineStatus.progress().translated());
                task.setTotalCount(engineStatus.progress().total());
            }

            if (Objects.nonNull(engineStatus.outputFilePath())) {
                task.setOutputFilePath(engineStatus.outputFilePath());
            }

            if (Objects.nonNull(engineStatus.originalBackupPath())) {
                task.setOriginalBackupPath(engineStatus.originalBackupPath());
            }

            if (Objects.nonNull(engineStatus.error())) {
                task.setErrorMessage(engineStatus.error());
            }

            // 检测状态变为 completed 或 failed 时触发对应处理
            if (newStatus == TaskStatus.completed && Objects.isNull(task.getDownloadUrl())) {
                handleTaskCompleted(task);
            } else if (newStatus == TaskStatus.failed) {
                handleTaskFailed(task);
            }

            // 同步成功，重置失败计数
            task.setSyncFailCount(0);
            translationTaskRepository.updateById(task);
        } catch (Exception e) {
            log.warn("[syncProgressFromEngine] 引擎同步失败 使用数据库状态 taskId {}", task.getTaskId(), e);
            incrementSyncFailCount(task);
        }
    }

    /**
     * 累加同步失败计数，超过阈值则标记任务为失败
     */
    private void incrementSyncFailCount(TranslationTask task) {
        var count = Objects.nonNull(task.getSyncFailCount()) ? task.getSyncFailCount() : 0;
        count++;
        task.setSyncFailCount(count);
        if (count >= MAX_SYNC_FAIL_COUNT) {
            log.error("[incrementSyncFailCount] 同步失败次数超过阈值 标记任务失败 taskId {} count {}", task.getTaskId(), count);
            task.setStatus(TaskStatus.failed);
            task.setErrorMessage("引擎同步失败次数超过 " + MAX_SYNC_FAIL_COUNT + " 次");
        }
        translationTaskRepository.updateById(task);
    }

    /**
     * 任务完成后处理：打包 zip → 上传 COS → 保存 download_url → 清理本地文件
     * 如果打包或上传失败，记录错误日志并保留本地文件
     *
     * @param task 翻译任务实体
     */
    private void handleTaskCompleted(TranslationTask task) {
        log.info("[handleTaskCompleted] 开始处理任务完成 taskId {}", task.getTaskId());

        var outputFilePath = task.getOutputFilePath();
        if (Objects.isNull(outputFilePath) || !Files.exists(Path.of(outputFilePath))) {
            log.error("[handleTaskCompleted] 输出文件不存在 taskId {} outputFilePath {}", task.getTaskId(), outputFilePath);
            task.setStatus(TaskStatus.failed);
            task.setErrorMessage("翻译输出文件不存在");
            cleanupLocalFiles(task, null);
            return;
        }

        Path zipPath = null;
        try {
            zipPath = createZipArchive(task);
            var zipFileName = getZipFileName(task);
            var cosKey = "translations/" + task.getTaskId() + "/" + zipFileName;
            var downloadUrl = cosService.uploadFile(zipPath, cosKey, zipFileName);
            task.setDownloadUrl(downloadUrl);
            log.info("[handleTaskCompleted] COS 上传成功 taskId {} downloadUrl {}", task.getTaskId(), downloadUrl);
            cleanupLocalFiles(task, zipPath);
        } catch (Exception e) {
            log.error("[handleTaskCompleted] 打包或上传失败 taskId {}", task.getTaskId(), e);
            // 打包或上传失败，保留本地文件，不改变任务状态
        }
    }

    /**
     * 任务失败后处理：清理本地临时文件
     *
     * @param task 翻译任务实体
     */
    private void handleTaskFailed(TranslationTask task) {
        log.info("[handleTaskFailed] 开始处理任务失败 taskId {}", task.getTaskId());
        cleanupLocalFiles(task, null);
    }

    /**
     * 将翻译输出文件和原始备份文件打包为 zip
     * zip 文件名使用原始文件名去掉 .esm 后缀加 .zip
     *
     * @param task 翻译任务实体
     * @return zip 文件路径
     * @throws IOException 打包失败时抛出
     */
    Path createZipArchive(TranslationTask task) throws IOException {
        var outputPath = Path.of(task.getOutputFilePath());
        var zipFileName = getZipFileName(task);
        var zipPath = outputPath.getParent().resolve(zipFileName);

        log.info("[createZipArchive] 开始打包 taskId {} zipPath {}", task.getTaskId(), zipPath);

        try (var zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            // 添加翻译输出文件
            if (Files.exists(outputPath)) {
                zos.putNextEntry(new ZipEntry(outputPath.getFileName().toString()));
                Files.copy(outputPath, zos);
                zos.closeEntry();
            }

            // 添加原始备份文件
            if (Objects.nonNull(task.getOriginalBackupPath())) {
                var backupPath = Path.of(task.getOriginalBackupPath());
                if (Files.exists(backupPath)) {
                    zos.putNextEntry(new ZipEntry(backupPath.getFileName().toString()));
                    Files.copy(backupPath, zos);
                    zos.closeEntry();
                }
            }
        }

        log.info("[createZipArchive] 打包完成 taskId {} zipPath {}", task.getTaskId(), zipPath);
        return zipPath;
    }

    /**
     * 清理任务相关的所有本地临时文件
     * 删除失败时仅记录警告日志，不抛出异常
     *
     * @param task    翻译任务实体
     * @param zipPath zip 文件路径（可为 null）
     */
    void cleanupLocalFiles(TranslationTask task, Path zipPath) {
        log.info("[cleanupLocalFiles] 开始清理本地文件 taskId {}", task.getTaskId());

        deleteFileQuietly(task.getFilePath(), task.getTaskId());
        deleteFileQuietly(task.getOutputFilePath(), task.getTaskId());
        deleteFileQuietly(task.getOriginalBackupPath(), task.getTaskId());

        if (Objects.nonNull(zipPath)) {
            deleteFileQuietly(zipPath.toString(), task.getTaskId());
        }
    }

    /**
     * 静默删除文件，失败时仅记录警告日志
     *
     * @param filePath 文件路径字符串
     * @param taskId   任务 ID（用于日志）
     */
    private void deleteFileQuietly(String filePath, String taskId) {
        if (Objects.isNull(filePath)) {
            return;
        }
        try {
            var deleted = Files.deleteIfExists(Path.of(filePath));
            if (deleted) {
                log.info("[deleteFileQuietly] 文件已删除 taskId {} filePath {}", taskId, filePath);
            }
        } catch (IOException e) {
            log.warn("[deleteFileQuietly] 文件删除失败 taskId {} filePath {}", taskId, filePath, e);
        }
    }

    /**
     * 根据任务原始文件名生成 zip 文件名
     * 将 .esm 后缀替换为 .zip
     *
     * @param task 翻译任务实体
     * @return zip 文件名
     */
    private String getZipFileName(TranslationTask task) {
        var fileName = task.getFileName();
        if (fileName.toLowerCase().endsWith(".esm")) {
            return fileName.substring(0, fileName.length() - 4) + ".zip";
        }
        return fileName + ".zip";
    }

    /**
     * 任务不存在异常
     */
    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String taskId) {
            super("翻译任务不存在 taskId " + taskId);
        }
    }
}
