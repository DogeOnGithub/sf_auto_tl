package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.starfield.api.client.EngineClient;
import com.starfield.api.dto.CacheSaveItem;
import com.starfield.api.dto.CacheSaveRequest;
import com.starfield.api.dto.ConfirmationPageResponse;
import com.starfield.api.dto.ConfirmationRecordResponse;
import com.starfield.api.dto.ConfirmationSaveItem;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationConfirmation;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.TranslationConfirmationRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 翻译确认服务，处理确认记录的 CRUD 和文件生成触发逻辑
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationConfirmationService {

    final TranslationConfirmationRepository confirmationRepository;
    final TranslationTaskRepository translationTaskRepository;
    final TranslationCacheService translationCacheService;
    final EngineClient engineClient;

    @Value("${api.base-url:http://localhost:8080}")
    private String callbackBaseUrl;

    private static final String STATUS_PENDING = "pending";
    private static final String STATUS_CONFIRMED = "confirmed";

    /**
     * 批量保存确认记录（由回调逻辑调用）
     *
     * @param taskId 任务 ID
     * @param items  待保存的确认记录项列表
     */
    public void saveConfirmationRecords(String taskId, List<ConfirmationSaveItem> items) {
        log.info("[saveConfirmationRecords] 保存确认记录 taskId {} itemsSize {}", taskId, items.size());

        var entities = items.stream()
                .map(item -> {
                    var entity = new TranslationConfirmation();
                    entity.setTaskId(taskId);
                    entity.setRecordId(item.recordId());
                    entity.setRecordType(item.recordType());
                    entity.setSourceText(item.sourceText());
                    entity.setTargetText(item.targetText());
                    entity.setStatus(STATUS_PENDING);
                    entity.setCreatedAt(LocalDateTime.now());
                    entity.setUpdatedAt(LocalDateTime.now());
                    return entity;
                })
                .collect(Collectors.toList());

        for (var entity : entities) {
            confirmationRepository.insert(entity);
        }

        log.info("[saveConfirmationRecords] 保存完成 taskId {} count {}", taskId, entities.size());
    }

    /**
     * 分页查询确认记录（支持状态过滤、关键字搜索）
     *
     * @param taskId  任务 ID
     * @param page    页码
     * @param size    每页大小
     * @param status  状态过滤（可选）
     * @param keyword 关键字搜索（可选）
     * @return 分页响应
     */
    public ConfirmationPageResponse listByTaskId(String taskId, int page, int size, String status, String keyword) {
        log.info("[listByTaskId] 查询确认记录 taskId {} page {} size {} status {} keyword {}", taskId, page, size, status, keyword);

        var wrapper = new LambdaQueryWrapper<TranslationConfirmation>()
                .eq(TranslationConfirmation::getTaskId, taskId)
                .orderByAsc(TranslationConfirmation::getId);

        if (Objects.nonNull(status) && !status.isBlank()) {
            wrapper.eq(TranslationConfirmation::getStatus, status.trim());
        }

        if (Objects.nonNull(keyword) && !keyword.isBlank()) {
            var kw = keyword.trim();
            wrapper.and(w -> w.apply("source_text ILIKE {0}", "%" + kw + "%")
                    .or().apply("target_text ILIKE {0}", "%" + kw + "%"));
        }

        var pageResult = confirmationRepository.selectPage(new Page<>(page, size), wrapper);

        var records = pageResult.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return new ConfirmationPageResponse(records, pageResult.getTotal(), pageResult.getCurrent(), pageResult.getPages());
    }

    /**
     * 编辑译文，不改变状态
     *
     * @param id         确认记录 ID
     * @param targetText 新的译文
     * @return 更新后的记录响应
     */
    public ConfirmationRecordResponse updateTargetText(Long id, String targetText) {
        log.info("[updateTargetText] 编辑译文 id {} targetText {}", id, targetText);

        var record = confirmationRepository.selectById(id);
        if (Objects.isNull(record)) {
            throw new ConfirmationNotFoundException(id);
        }

        record.setTargetText(targetText);
        record.setUpdatedAt(LocalDateTime.now());
        confirmationRepository.updateById(record);

        log.info("[updateTargetText] 编辑完成 id {}", id);
        return toResponse(record);
    }

    /**
     * 逐条确认（幂等：已确认的记录不会报错）
     *
     * @param taskId 任务 ID
     * @param id     确认记录 ID
     */
    public void confirmSingle(String taskId, Long id) {
        log.info("[confirmSingle] 逐条确认 taskId {} id {}", taskId, id);

        var record = confirmationRepository.selectById(id);
        if (Objects.isNull(record)) {
            throw new ConfirmationNotFoundException(id);
        }

        if (STATUS_CONFIRMED.equals(record.getStatus())) {
            log.info("[confirmSingle] 记录已确认 跳过 id {}", id);
            return;
        }

        record.setStatus(STATUS_CONFIRMED);
        record.setUpdatedAt(LocalDateTime.now());
        confirmationRepository.updateById(record);

        log.info("[confirmSingle] 确认完成 id {}", id);
    }

    /**
     * 批量确认
     *
     * @param taskId 任务 ID
     * @param ids    确认记录 ID 列表
     */
    public void batchConfirm(String taskId, List<Long> ids) {
        log.info("[batchConfirm] 批量确认 taskId {} idsSize {}", taskId, ids.size());

        var updateWrapper = new LambdaUpdateWrapper<TranslationConfirmation>()
                .in(TranslationConfirmation::getId, ids)
                .eq(TranslationConfirmation::getTaskId, taskId)
                .set(TranslationConfirmation::getStatus, STATUS_CONFIRMED)
                .set(TranslationConfirmation::getUpdatedAt, LocalDateTime.now());

        confirmationRepository.update(null, updateWrapper);

        log.info("[batchConfirm] 批量确认完成 taskId {} count {}", taskId, ids.size());
    }

    /**
     * 全部确认（将该任务下所有 pending 记录更新为 confirmed）
     *
     * @param taskId 任务 ID
     */
    public void confirmAll(String taskId) {
        log.info("[confirmAll] 全部确认 taskId {}", taskId);

        var updateWrapper = new LambdaUpdateWrapper<TranslationConfirmation>()
                .eq(TranslationConfirmation::getTaskId, taskId)
                .eq(TranslationConfirmation::getStatus, STATUS_PENDING)
                .set(TranslationConfirmation::getStatus, STATUS_CONFIRMED)
                .set(TranslationConfirmation::getUpdatedAt, LocalDateTime.now());

        confirmationRepository.update(null, updateWrapper);

        log.info("[confirmAll] 全部确认完成 taskId {}", taskId);
    }

    /**
     * 触发文件生成：校验全部已确认 → 写入翻译缓存 → 提交引擎组装
     *
     * @param taskId 任务 ID
     */
    public void generateFile(String taskId) {
        log.info("[generateFile] 触发文件生成 taskId {}", taskId);

        var task = translationTaskRepository.selectById(taskId);
        if (Objects.isNull(task)) {
            throw new InvalidTaskStateException("任务不存在 taskId " + taskId);
        }

        if (task.getStatus() != TaskStatus.pending_confirmation) {
            throw new InvalidTaskStateException("任务状态不是 pending_confirmation taskId " + taskId + " status " + task.getStatus());
        }

        var pendingCount = confirmationRepository.selectCount(
                new LambdaQueryWrapper<TranslationConfirmation>()
                        .eq(TranslationConfirmation::getTaskId, taskId)
                        .eq(TranslationConfirmation::getStatus, STATUS_PENDING)
        );

        if (pendingCount > 0) {
            throw new PendingRecordsExistException(taskId, pendingCount);
        }

        var confirmedRecords = confirmationRepository.selectList(
                new LambdaQueryWrapper<TranslationConfirmation>()
                        .eq(TranslationConfirmation::getTaskId, taskId)
                        .eq(TranslationConfirmation::getStatus, STATUS_CONFIRMED)
        );

        // 将已确认的译文写入翻译缓存
        var cacheItems = confirmedRecords.stream()
                .map(r -> new CacheSaveItem(
                        "",
                        r.getRecordType(),
                        r.getSourceText(),
                        r.getTargetText()
                ))
                .collect(Collectors.toList());

        var cacheSaveRequest = new CacheSaveRequest(taskId, task.getTargetLang(), cacheItems);
        translationCacheService.save(cacheSaveRequest);
        log.info("[generateFile] 翻译缓存写入完成 taskId {} itemsCount {}", taskId, cacheItems.size());

        // 提交引擎组装
        var assemblyItems = confirmedRecords.stream()
                .map(r -> new EngineClient.AssemblyItem(
                        r.getRecordId(),
                        r.getRecordType(),
                        r.getSourceText(),
                        r.getTargetText()
                ))
                .collect(Collectors.toList());

        var callbackUrl = callbackBaseUrl + "/api/tasks/" + taskId + "/progress";
        var absoluteFilePath = java.nio.file.Paths.get(task.getFilePath()).toAbsolutePath().toString();
        var request = new EngineClient.EngineAssemblyRequest(
                taskId,
                absoluteFilePath,
                assemblyItems,
                callbackUrl
        );

        engineClient.submitAssembly(request);

        task.setStatus(TaskStatus.assembling);
        task.setUpdatedAt(LocalDateTime.now());
        translationTaskRepository.updateById(task);

        log.info("[generateFile] 组装任务已提交 taskId {} itemsCount {}", taskId, assemblyItems.size());
    }

    /**
     * 将确认记录实体转换为响应 DTO
     *
     * @param record 确认记录实体
     * @return 确认记录响应
     */
    private ConfirmationRecordResponse toResponse(TranslationConfirmation record) {
        return new ConfirmationRecordResponse(
                record.getId(),
                record.getTaskId(),
                record.getRecordId(),
                record.getRecordType(),
                record.getSourceText(),
                record.getTargetText(),
                record.getStatus(),
                record.getCreatedAt(),
                record.getUpdatedAt()
        );
    }

    /**
     * 确认记录不存在异常
     */
    public static class ConfirmationNotFoundException extends RuntimeException {
        public ConfirmationNotFoundException(Long id) {
            super("确认记录不存在 id " + id);
        }
    }

    /**
     * 存在未确认记录异常
     */
    public static class PendingRecordsExistException extends RuntimeException {
        private final long pendingCount;

        public PendingRecordsExistException(String taskId, long pendingCount) {
            super("任务存在未确认记录 taskId " + taskId + " pendingCount " + pendingCount);
            this.pendingCount = pendingCount;
        }

        public long getPendingCount() {
            return pendingCount;
        }
    }

    /**
     * 无效任务状态异常
     */
    public static class InvalidTaskStateException extends RuntimeException {
        public InvalidTaskStateException(String message) {
            super(message);
        }
    }
}
