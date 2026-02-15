package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.starfield.api.client.EngineClient;
import com.starfield.api.dto.TaskResponse;
import com.starfield.api.entity.Creation;
import com.starfield.api.entity.CreationVersion;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.CreationRepository;
import com.starfield.api.repository.CreationVersionRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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
    final EngineClient engineClient;

    /**
     * 查询翻译任务状态和进度，尝试从引擎同步最新进度
     *
     * @param taskId 任务 ID
     * @return 任务响应 DTO
     */
    public TaskResponse getTask(String taskId) {
        log.info("[getTask] 查询任务 taskId {}", taskId);

        var task = translationTaskRepository.selectById(taskId);
        if (Objects.isNull(task)) {
            log.warn("[getTask] 任务不存在 taskId {}", taskId);
            throw new TaskNotFoundException(taskId);
        }

        syncProgressFromEngine(task);

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
     * 转换任务实体为响应 DTO（含 creation 信息）
     */
    private TaskResponse toResponse(TranslationTask task) {
        TaskResponse.CreationInfo creationInfo = null;
        if (Objects.nonNull(task.getCreationVersionId())) {
            creationInfo = buildCreationInfo(task.getCreationVersionId());
        }
        return new TaskResponse(
                task.getTaskId(),
                task.getFileName(),
                task.getStatus().name(),
                new TaskResponse.Progress(task.getTranslatedCount(), task.getTotalCount()),
                creationInfo,
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

    private static final int MAX_SYNC_FAIL_COUNT = 100;

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
     * 任务不存在异常
     */
    public static class TaskNotFoundException extends RuntimeException {
        public TaskNotFoundException(String taskId) {
            super("翻译任务不存在 taskId " + taskId);
        }
    }
}
