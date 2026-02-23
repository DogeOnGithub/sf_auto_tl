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

    /** 每批 SQL 操作的最大记录数 防止 SQL 语句过长 */
    private static final int BATCH_CHUNK_SIZE = 500;

    /**
     * 批量保存确认记录（由回调逻辑调用）
     * 使用 ON CONFLICT (task_id, record_id) DO NOTHING 防止重复插入
     * 按 BATCH_CHUNK_SIZE 分片执行 避免单条 SQL 过长
     *
     * @param taskId 任务 ID
     * @param items  待保存的确认记录项列表
     */
    public void saveConfirmationRecords(String taskId, List<ConfirmationSaveItem> items) {
        log.info("[saveConfirmationRecords] 保存确认记录 taskId {} itemsSize {}", taskId, items.size());

        if (items.isEmpty()) {
            return;
        }

        // 内存去重 同一批次中可能有重复的 recordId
        var seen = new java.util.HashSet<String>();
        var dedupedItems = items.stream()
                .filter(item -> seen.add(item.recordId()))
                .collect(Collectors.toList());

        var entities = dedupedItems.stream()
                .map(item -> {
                    var entity = new TranslationConfirmation();
                    entity.setTaskId(taskId);
                    entity.setRecordId(item.recordId());
                    entity.setRecordType(item.recordType());
                    entity.setSourceText(item.sourceText());
                    entity.setTargetText(item.targetText());
                    entity.setStatus(STATUS_PENDING);
                    return entity;
                })
                .collect(Collectors.toList());

        // 分片批量插入 ON CONFLICT DO NOTHING
        for (var i = 0; i < entities.size(); i += BATCH_CHUNK_SIZE) {
            var chunk = entities.subList(i, Math.min(i + BATCH_CHUNK_SIZE, entities.size()));
            confirmationRepository.batchInsertIgnore(chunk);
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
     * 统计指定任务的确认记录总数
     *
     * @param taskId 任务 ID
     * @return 确认记录数
     */
    public long countByTaskId(String taskId) {
        return confirmationRepository.selectCount(
                new LambdaQueryWrapper<TranslationConfirmation>()
                        .eq(TranslationConfirmation::getTaskId, taskId)
        );
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
     * 批量确认 按 BATCH_CHUNK_SIZE 分片执行 避免 IN 子句过长
     *
     * @param taskId 任务 ID
     * @param ids    确认记录 ID 列表
     */
    public void batchConfirm(String taskId, List<Long> ids) {
        log.info("[batchConfirm] 批量确认 taskId {} idsSize {}", taskId, ids.size());

        for (var i = 0; i < ids.size(); i += BATCH_CHUNK_SIZE) {
            var chunk = ids.subList(i, Math.min(i + BATCH_CHUNK_SIZE, ids.size()));
            confirmationRepository.batchUpdateStatus(taskId, chunk, STATUS_CONFIRMED);
        }

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
     * 批量替换译文中的文本（支持指定 ID 列表或全任务范围）
     * 当指定 ID 列表时按 BATCH_CHUNK_SIZE 分片查询 避免 IN 子句过长
     *
     * @param taskId    任务 ID
     * @param ids       指定的记录 ID 列表（为空时替换全任务范围）
     * @param searchStr 要查找的文本
     * @param replaceStr 替换为的文本
     * @return 实际替换的记录数
     */
    public int batchReplace(String taskId, List<Long> ids, String searchStr, String replaceStr) {
        log.info("[batchReplace] 批量替换 taskId {} idsSize {} search {} replace {}", taskId, Objects.nonNull(ids) ? ids.size() : 0, searchStr, replaceStr);

        var matchedRecords = new java.util.ArrayList<TranslationConfirmation>();

        if (Objects.nonNull(ids) && !ids.isEmpty()) {
            // 分片查询 避免 IN 子句过长
            for (var i = 0; i < ids.size(); i += BATCH_CHUNK_SIZE) {
                var chunk = ids.subList(i, Math.min(i + BATCH_CHUNK_SIZE, ids.size()));
                var wrapper = new LambdaQueryWrapper<TranslationConfirmation>()
                        .eq(TranslationConfirmation::getTaskId, taskId)
                        .apply("target_text LIKE {0}", "%" + searchStr + "%")
                        .in(TranslationConfirmation::getId, chunk);
                matchedRecords.addAll(confirmationRepository.selectList(wrapper));
            }
        } else {
            var wrapper = new LambdaQueryWrapper<TranslationConfirmation>()
                    .eq(TranslationConfirmation::getTaskId, taskId)
                    .apply("target_text LIKE {0}", "%" + searchStr + "%");
            matchedRecords.addAll(confirmationRepository.selectList(wrapper));
        }

        if (matchedRecords.isEmpty()) {
            log.info("[batchReplace] 无匹配记录 taskId {}", taskId);
            return 0;
        }

        var replacedCount = 0;
        for (var record : matchedRecords) {
            var newText = record.getTargetText().replace(searchStr, replaceStr);
            if (!newText.equals(record.getTargetText())) {
                record.setTargetText(newText);
                record.setUpdatedAt(LocalDateTime.now());
                confirmationRepository.updateById(record);
                replacedCount++;
            }
        }

        log.info("[batchReplace] 替换完成 taskId {} replacedCount {}", taskId, replacedCount);
        return replacedCount;
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
        // recordId 格式: RECORD_TYPE:FORM_ID:SUBRECORD_TYPE 或 RECORD_TYPE:FORM_ID:SUBRECORD_TYPE#N
        // 从中提取 recordType 和 subrecordType（去掉 #N 序号后缀）
        var cacheItems = confirmedRecords.stream()
                .map(r -> {
                    var parts = r.getRecordId().split(":");
                    var recordType = parts.length > 0 ? parts[0] : "";
                    var subrecordTypeFull = parts.length > 2 ? parts[2] : "";
                    var hashIdx = subrecordTypeFull.indexOf('#');
                    var subrecordType = hashIdx >= 0 ? subrecordTypeFull.substring(0, hashIdx) : subrecordTypeFull;
                    return new CacheSaveItem(
                            recordType,
                            subrecordType,
                            r.getSourceText(),
                            r.getTargetText()
                    );
                })
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
