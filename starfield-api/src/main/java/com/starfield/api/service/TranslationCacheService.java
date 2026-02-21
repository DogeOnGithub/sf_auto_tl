package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.starfield.api.dto.*;
import com.starfield.api.entity.TranslationCache;
import com.starfield.api.repository.TranslationCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 翻译缓存服务，处理缓存的批量查询和 UPSERT 保存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationCacheService {

    final TranslationCacheRepository translationCacheRepository;

    /**
     * 批量查询缓存，返回每条词条的命中状态和 target_text
     *
     * @param request 缓存查询请求
     * @return 缓存查询响应
     */
    public CacheQueryResponse query(CacheQueryRequest request) {
        log.info("[query] 收到缓存查询请求 targetLang {} itemsSize {}", request.targetLang(), Objects.nonNull(request.items()) ? request.items().size() : 0);

        if (Objects.isNull(request.items()) || request.items().isEmpty()) {
            log.info("[query] 查询列表为空 返回空结果");
            return new CacheQueryResponse(Collections.emptyList());
        }

        var resultItems = request.items().stream()
                .map(item -> queryItem(item, request.targetLang()))
                .collect(Collectors.toList());

        var hitCount = resultItems.stream().filter(CacheQueryResultItem::hit).count();
        log.info("[query] 查询完成 总数 {} 命中 {}", resultItems.size(), hitCount);
        return new CacheQueryResponse(resultItems);
    }

    /**
     * 批量保存翻译结果到缓存（UPSERT 语义）
     *
     * @param request 缓存保存请求
     */
    public void save(CacheSaveRequest request) {
        log.info("[save] 收到缓存保存请求 taskId {} targetLang {} itemsSize {}", request.taskId(), request.targetLang(), Objects.nonNull(request.items()) ? request.items().size() : 0);

        if (Objects.isNull(request.items()) || request.items().isEmpty()) {
            log.info("[save] 保存列表为空 跳过");
            return;
        }

        for (var item : request.items()) {
            upsertItem(item, request.taskId(), request.targetLang());
        }

        log.info("[save] 保存完成 数量 {}", request.items().size());
    }

    /**
     * 分页查询缓存列表（支持关键词搜索）
     *
     * @param page    页码
     * @param size    每页大小
     * @param keyword 搜索关键词（可选）
     * @return 分页响应
     */
    public CachePageResponse list(int page, int size, String keyword) {
        log.info("[list] 查询缓存列表 page {} size {} keyword {}", page, size, keyword);

        var wrapper = new LambdaQueryWrapper<TranslationCache>().orderByDesc(TranslationCache::getUpdatedAt);
        if (Objects.nonNull(keyword) && !keyword.isBlank()) {
            var kw = keyword.trim();
            wrapper.and(w -> w.apply("source_text ILIKE {0}", "%" + kw + "%")
                    .or().apply("target_text ILIKE {0}", "%" + kw + "%")
                    .or().apply("subrecord_type ILIKE {0}", "%" + kw + "%")
                    .or().apply("record_type ILIKE {0}", "%" + kw + "%")
                    .or().apply("task_id ILIKE {0}", "%" + kw + "%"));
        }

        var pageResult = translationCacheRepository.selectPage(new Page<>(page, size), wrapper);
        var records = pageResult.getRecords().stream()
                .map(c -> new CacheEntryResponse(c.getId(), c.getTaskId(), c.getRecordType(), c.getSubrecordType(),
                        c.getSourceText(), c.getTargetText(), c.getTargetLang(),
                        c.getCreatedAt(), c.getUpdatedAt()))
                .collect(Collectors.toList());

        return new CachePageResponse(records, pageResult.getTotal(), pageResult.getCurrent(), pageResult.getPages());
    }

    /**
     * 更新缓存记录的译文
     *
     * @param id      缓存记录 ID
     * @param request 更新请求
     * @return 更新后的记录
     */
    public CacheEntryResponse update(Long id, CacheUpdateRequest request) {
        log.info("[update] 更新缓存 id {} targetText {}", id, request.targetText());
        var cache = translationCacheRepository.selectById(id);
        if (Objects.isNull(cache)) {
            throw new RuntimeException("缓存记录不存在 id " + id);
        }
        cache.setTargetText(request.targetText());
        cache.setUpdatedAt(LocalDateTime.now());
        translationCacheRepository.updateById(cache);
        return new CacheEntryResponse(cache.getId(), cache.getTaskId(), cache.getRecordType(), cache.getSubrecordType(),
                cache.getSourceText(), cache.getTargetText(), cache.getTargetLang(),
                cache.getCreatedAt(), cache.getUpdatedAt());
    }

    /**
     * 删除缓存记录
     *
     * @param id 缓存记录 ID
     */
    public void delete(Long id) {
        log.info("[delete] 删除缓存 id {}", id);
        var cache = translationCacheRepository.selectById(id);
        if (Objects.isNull(cache)) {
            throw new RuntimeException("缓存记录不存在 id " + id);
        }
        translationCacheRepository.deleteById(id);
        log.info("[delete] 缓存删除成功 id {}", id);
    }

    /**
     * 批量删除缓存记录
     *
     * @param ids 缓存记录 ID 列表
     */
    public void batchDelete(List<Long> ids) {
        log.info("[batchDelete] 批量删除缓存 count {}", ids.size());
        translationCacheRepository.deleteBatchIds(ids);
        log.info("[batchDelete] 批量删除完成 count {}", ids.size());
    }

    /**
     * 根据任务 ID 删除所有关联的缓存记录
     *
     * @param taskId 任务 ID
     * @return 删除的记录数
     */
    public long deleteByTaskId(String taskId) {
        log.info("[deleteByTaskId] 根据任务ID删除缓存 taskId {}", taskId);
        var wrapper = new LambdaQueryWrapper<TranslationCache>()
                .eq(TranslationCache::getTaskId, taskId);
        var count = translationCacheRepository.delete(wrapper);
        log.info("[deleteByTaskId] 删除完成 taskId {} count {}", taskId, count);
        return count;
    }


    /**
     * 查询单条词条的缓存命中情况
     */
    private CacheQueryResultItem queryItem(CacheQueryItem item, String targetLang) {
        var wrapper = new LambdaQueryWrapper<TranslationCache>()
                .eq(TranslationCache::getRecordType, Objects.nonNull(item.recordType()) ? item.recordType() : "")
                .eq(TranslationCache::getSubrecordType, item.subrecordType())
                .eq(TranslationCache::getSourceText, item.sourceText())
                .eq(TranslationCache::getTargetLang, targetLang);
        var cached = translationCacheRepository.selectOne(wrapper);

        if (Objects.nonNull(cached)) {
            return new CacheQueryResultItem(item.recordId(), true, cached.getTargetText());
        }
        return new CacheQueryResultItem(item.recordId(), false, null);
    }

    /**
     * UPSERT 单条缓存记录：存在则更新，不存在则插入
     */
    private void upsertItem(CacheSaveItem item, String taskId, String targetLang) {
        var recordType = Objects.nonNull(item.recordType()) ? item.recordType() : "";
        var wrapper = new LambdaQueryWrapper<TranslationCache>()
                .eq(TranslationCache::getRecordType, recordType)
                .eq(TranslationCache::getSubrecordType, item.subrecordType())
                .eq(TranslationCache::getSourceText, item.sourceText())
                .eq(TranslationCache::getTargetLang, targetLang);
        var existing = translationCacheRepository.selectOne(wrapper);

        if (Objects.nonNull(existing)) {
            existing.setTargetText(item.targetText());
            existing.setTaskId(taskId);
            existing.setUpdatedAt(LocalDateTime.now());
            translationCacheRepository.updateById(existing);
            log.debug("[upsertItem] 更新缓存 recordType {} subrecordType {} targetLang {}", recordType, item.subrecordType(), targetLang);
        } else {
            var cache = new TranslationCache();
            cache.setTaskId(taskId);
            cache.setRecordType(recordType);
            cache.setSubrecordType(item.subrecordType());
            cache.setSourceText(item.sourceText());
            cache.setTargetText(item.targetText());
            cache.setTargetLang(targetLang);
            cache.setCreatedAt(LocalDateTime.now());
            cache.setUpdatedAt(LocalDateTime.now());
            translationCacheRepository.insert(cache);
            log.debug("[upsertItem] 插入缓存 recordType {} subrecordType {} targetLang {}", recordType, item.subrecordType(), targetLang);
        }
    }
}
