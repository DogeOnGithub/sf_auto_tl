package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.starfield.api.client.EngineClient;
import com.starfield.api.config.CosProperties;
import com.starfield.api.dto.ConfirmationSaveItem;
import com.starfield.api.dto.ProgressCallbackRequest;
import com.starfield.api.dto.TaskPageResponse;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
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
    final com.starfield.api.repository.TranslationConfirmationRepository translationConfirmationRepository;
    final EngineClient engineClient;
    final CosService cosService;
    final CosProperties cosProperties;
    final TranslationConfirmationService translationConfirmationService;

    @Value("${storage.upload-dir:./uploads}")
    private String uploadDir;

    /** uploads 目录大小阈值 20GB */
    private static final long UPLOAD_DIR_SIZE_THRESHOLD = 20L * 1024 * 1024 * 1024;

    /** 任务过期天数 */
    private static final int TASK_EXPIRATION_DAYS = 5;

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
     * 分页查询翻译任务列表
     *
     * @param page 页码
     * @param size 每页大小
     * @return 分页响应
     */
    public TaskPageResponse listTasksPaged(int page, int size) {
        log.info("[listTasksPaged] 分页查询任务 page {} size {}", page, size);
        var wrapper = new QueryWrapper<TranslationTask>()
                .orderByDesc("created_at");
        var pageResult = translationTaskRepository.selectPage(new Page<>(page, size), wrapper);
        var records = pageResult.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return new TaskPageResponse(records, pageResult.getTotal(), pageResult.getCurrent(), pageResult.getPages());
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
        if (currentStatus == TaskStatus.completed || currentStatus == TaskStatus.failed || currentStatus == TaskStatus.pending_confirmation) {
            log.info("[handleProgressCallback] 任务已终态 跳过 taskId {} status {}", taskId, currentStatus);
            return;
        }

        var newStatus = TaskStatus.valueOf(request.status());
        var isConfirmationMode = "confirmation".equals(task.getConfirmationMode());

        // confirmation 模式下 收到 items 时增量写入 translation_confirmation 表
        if (isConfirmationMode && Objects.nonNull(request.items()) && !request.items().isEmpty()) {
            var saveItems = request.items().stream()
                    .map(item -> new ConfirmationSaveItem(
                            item.recordId(), item.recordType(), item.sourceText(), item.targetText()))
                    .collect(Collectors.toList());
            translationConfirmationService.saveConfirmationRecords(taskId, saveItems);
            log.info("[handleProgressCallback] confirmation 模式增量写入确认记录 taskId {} count {}", taskId, saveItems.size());
        }

        // confirmation 模式下 翻译阶段引擎回调 completed 状态时拦截 改为 pending_confirmation
        // （Engine 在 confirmation 模式下跳过重组步骤 直接回调 completed）
        // 当任务已经是 assembling（由 generateFile 设置）时不再拦截
        if (isConfirmationMode && newStatus == TaskStatus.completed && currentStatus != TaskStatus.assembling) {
            log.info("[handleProgressCallback] confirmation 模式拦截 completed 状态 改为 pending_confirmation taskId {}", taskId);

            // 校验确认记录数与总词条数是否一致
            var confirmationCount = translationConfirmationService.countByTaskId(taskId);
            var totalCount = Objects.nonNull(request.progress()) ? request.progress().total() : 0;
            if (confirmationCount != totalCount) {
                log.warn("[handleProgressCallback] 确认记录数与总词条数不一致 taskId {} confirmationCount {} totalCount {}", taskId, confirmationCount, totalCount);
            }

            task.setStatus(TaskStatus.pending_confirmation);

            if (Objects.nonNull(request.progress())) {
                task.setTranslatedCount(request.progress().translated());
                task.setTotalCount(request.progress().total());
            }

            task.setSyncFailCount(0);
            translationTaskRepository.updateById(task);
            return;
        }

        // direct 模式或其他状态 保持原有逻辑
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
                task.getConfirmationMode(),
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
                .notIn("status", TaskStatus.completed.name(), TaskStatus.failed.name(), TaskStatus.expired.name(), TaskStatus.pending_confirmation.name());
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
        if (status == TaskStatus.completed || status == TaskStatus.failed || status == TaskStatus.pending_confirmation) {
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

        var folderName = zipFileName.replace(".zip", "") + "/";
        try (var zos = new ZipOutputStream(Files.newOutputStream(zipPath))) {
            if (Files.exists(outputPath)) {
                zos.putNextEntry(new ZipEntry(folderName + task.getFileName()));
                Files.copy(outputPath, zos);
                zos.closeEntry();
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
        var dotIndex = fileName.lastIndexOf('.');
        var baseName = dotIndex > 0 ? fileName.substring(0, dotIndex) : fileName;
        return baseName + ".zip";
    }

    /**
     * 定时清理 uploads 目录中的孤立文件
     * 仅当目录总大小超过 20GB 时触发清理
     * 清理范围：已完成（有下载链接）或已失败的任务关联文件
     */
    public void cleanupUploadsIfOversized() {
        var uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            return;
        }

        try {
            var totalSize = calculateDirectorySize(uploadPath);
            var totalSizeMB = totalSize / (1024 * 1024);
            log.info("[cleanupUploadsIfOversized] uploads 目录大小 {}MB 阈值 {}MB", totalSizeMB, UPLOAD_DIR_SIZE_THRESHOLD / (1024 * 1024));

            if (totalSize < UPLOAD_DIR_SIZE_THRESHOLD) {
                return;
            }

            log.info("[cleanupUploadsIfOversized] 超过阈值 开始清理");

            // 查询已完成且有下载链接的任务
            var completedWrapper = new QueryWrapper<TranslationTask>()
                    .eq("status", TaskStatus.completed.name())
                    .isNotNull("download_url");
            var completedTasks = translationTaskRepository.selectList(completedWrapper);

            // 查询已失败的任务
            var failedWrapper = new QueryWrapper<TranslationTask>()
                    .eq("status", TaskStatus.failed.name());
            var failedTasks = translationTaskRepository.selectList(failedWrapper);

            var cleanedCount = 0;

            for (var task : completedTasks) {
                cleanedCount += cleanupTaskFiles(task);
            }

            for (var task : failedTasks) {
                cleanedCount += cleanupTaskFiles(task);
            }

            var newSize = calculateDirectorySize(uploadPath);
            log.info("[cleanupUploadsIfOversized] 清理完成 删除文件数 {} 清理前 {}MB 清理后 {}MB",
                    cleanedCount, totalSizeMB, newSize / (1024 * 1024));

        } catch (Exception e) {
            log.error("[cleanupUploadsIfOversized] 清理异常", e);
        }
    }

    /**
     * 清理单个任务关联的本地文件 返回删除的文件数
     */
    private int cleanupTaskFiles(TranslationTask task) {
        var count = 0;
        if (deleteFileIfExists(task.getFilePath())) count++;
        if (deleteFileIfExists(task.getOutputFilePath())) count++;
        if (deleteFileIfExists(task.getOriginalBackupPath())) count++;
        return count;
    }

    /**
     * 删除文件（如果存在） 返回是否成功删除
     */
    private boolean deleteFileIfExists(String filePath) {
        if (Objects.isNull(filePath)) {
            return false;
        }
        try {
            return Files.deleteIfExists(Path.of(filePath));
        } catch (IOException e) {
            log.warn("[deleteFileIfExists] 文件删除失败 filePath {}", filePath, e);
            return false;
        }
    }

    /**
     * 计算目录总大小（字节）
     */
    private long calculateDirectorySize(Path dir) throws IOException {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .mapToLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    })
                    .sum();
        }
    }

    /**
     * 手动清理单个翻译任务：删除 COS 文件并标记为 expired
     *
     * @param taskId 任务 ID
     */
    public void expireTask(String taskId) {
        log.info("[expireTask] 手动清理任务 taskId {}", taskId);

        var task = translationTaskRepository.selectById(taskId);
        if (Objects.isNull(task)) {
            throw new TaskNotFoundException(taskId);
        }

        if (task.getStatus() == TaskStatus.expired) {
            log.info("[expireTask] 任务已过期 无需重复清理 taskId {}", taskId);
            return;
        }

        // 关联了未删除的 creation version 时禁止清理
        if (Objects.nonNull(task.getCreationVersionId())) {
            var version = creationVersionRepository.selectById(task.getCreationVersionId());
            if (Objects.nonNull(version)) {
                throw new TaskLinkedToCreationException(taskId, version.getCreationId());
            }
        }

        try {
            deleteCosFile(task);
        } catch (Exception e) {
            log.warn("[expireTask] COS 文件删除失败 taskId {} 继续标记过期", taskId, e);
        }

        // 清理本地文件（原始文件、翻译文件、备份文件）
        deleteLocalFile(task.getFilePath(), taskId);
        deleteLocalFile(task.getOutputFilePath(), taskId);
        deleteLocalFile(task.getOriginalBackupPath(), taskId);

        // 兜底：根据 filePath 推算 translated 和 backup 路径并尝试删除（防止 DB 字段为 null 时残留）
        if (Objects.nonNull(task.getFilePath()) && !task.getFilePath().isBlank()) {
            var basePath = task.getFilePath();
            var dotIdx = basePath.lastIndexOf('.');
            if (dotIdx > 0) {
                var name = basePath.substring(0, dotIdx);
                var ext = basePath.substring(dotIdx);
                deleteLocalFile(name + "_translated" + ext, taskId);
                deleteLocalFile(name + "_backup" + ext, taskId);
            }
        }

        // 清理确认记录
        try {
            var deletedConfirmations = translationConfirmationRepository.delete(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.starfield.api.entity.TranslationConfirmation>()
                            .eq(com.starfield.api.entity.TranslationConfirmation::getTaskId, taskId)
            );
            if (deletedConfirmations > 0) {
                log.info("[expireTask] 确认记录已清理 taskId {} count {}", taskId, deletedConfirmations);
            }
        } catch (Exception e) {
            log.warn("[expireTask] 确认记录清理失败 taskId {} 继续标记过期", taskId, e);
        }

        task.setStatus(TaskStatus.expired);
        task.setDownloadUrl(null);
        translationTaskRepository.updateById(task);
        log.info("[expireTask] 任务已标记过期 taskId {}", taskId);
    }

    /**
     * 批量清理翻译任务：删除 COS 文件并标记为 expired
     *
     * @param taskIds 任务 ID 列表
     * @return 成功清理的数量
     */
    public int batchExpireTasks(List<String> taskIds) {
        log.info("[batchExpireTasks] 批量清理任务 count {}", taskIds.size());
        var expiredCount = 0;
        for (var taskId : taskIds) {
            try {
                expireTask(taskId);
                expiredCount++;
            } catch (Exception e) {
                log.warn("[batchExpireTasks] 清理失败 taskId {}", taskId, e);
            }
        }
        log.info("[batchExpireTasks] 批量清理完成 expiredCount {}", expiredCount);
        return expiredCount;
    }

    /**
     * 清理过期任务的 COS 文件并标记为 expired
     * 条件：任务创建超过 5 天且未关联 creation
     */
    public void cleanupExpiredTasks() {
        var cutoff = LocalDateTime.now().minusDays(TASK_EXPIRATION_DAYS);
        var wrapper = new QueryWrapper<TranslationTask>()
                .eq("status", TaskStatus.completed.name())
                .isNull("creation_version_id")
                .isNotNull("download_url")
                .le("created_at", cutoff);
        var tasks = translationTaskRepository.selectList(wrapper);

        if (tasks.isEmpty()) {
            log.info("[cleanupExpiredTasks] 无过期任务需要清理");
            return;
        }

        log.info("[cleanupExpiredTasks] 发现过期任务 count {}", tasks.size());

        var expiredCount = 0;
        for (var task : tasks) {
            try {
                deleteCosFile(task);
                task.setStatus(TaskStatus.expired);
                task.setDownloadUrl(null);
                translationTaskRepository.updateById(task);
                expiredCount++;
                log.info("[cleanupExpiredTasks] 任务已过期 taskId {}", task.getTaskId());
            } catch (Exception e) {
                log.error("[cleanupExpiredTasks] 清理失败 taskId {}", task.getTaskId(), e);
            }
        }

        log.info("[cleanupExpiredTasks] 清理完成 expiredCount {}", expiredCount);
    }

    /**
     * 删除任务关联的 COS 文件
     *
     * @param task 翻译任务
     */
    private void deleteCosFile(TranslationTask task) {
        var downloadUrl = task.getDownloadUrl();
        if (Objects.isNull(downloadUrl)) {
            return;
        }
        var baseUrl = cosProperties.baseUrl();
        if (downloadUrl.startsWith(baseUrl)) {
            var cosKey = downloadUrl.substring(baseUrl.length() + 1);
            cosService.deleteObject(cosKey);
            log.info("[deleteCosFile] COS 文件已删除 taskId {} cosKey {}", task.getTaskId(), cosKey);
        } else {
            log.warn("[deleteCosFile] 下载链接格式不匹配 taskId {} downloadUrl {}", task.getTaskId(), downloadUrl);
        }
    }

    /**
     * 删除本地文件（静默处理异常）
     *
     * @param filePath 文件路径
     * @param taskId   任务 ID（用于日志）
     */
    private void deleteLocalFile(String filePath, String taskId) {
        if (Objects.isNull(filePath) || filePath.isBlank()) {
            return;
        }
        try {
            var path = Paths.get(filePath);
            if (Files.exists(path)) {
                Files.delete(path);
                log.info("[deleteLocalFile] 本地文件已删除 taskId {} path {}", taskId, filePath);
            }
        } catch (Exception e) {
            log.warn("[deleteLocalFile] 本地文件删除失败 taskId {} path {}", taskId, filePath, e);
        }
    }

    /**
     * 任务不存在异常
     */
    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String taskId) {
            super("翻译任务不存在 taskId " + taskId);
        }
    }

    /**
     * 任务关联了 creation 版本，不允许清理
     */
    public static class TaskLinkedToCreationException extends RuntimeException {
        public TaskLinkedToCreationException(String taskId, Long creationId) {
            super("任务关联了作品 无法清理 taskId " + taskId + " creationId " + creationId);
        }
    }
}
