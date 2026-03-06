package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.starfield.api.dto.*;
import com.starfield.api.entity.TranslationCache;
import com.starfield.api.repository.TranslationCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 翻译缓存服务，处理缓存的批量查询和 UPSERT 保存
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TranslationCacheService {

    final TranslationCacheRepository translationCacheRepository;

    /** 每批 SQL 操作的最大记录数 防止 SQL 语句过长 */
    private static final int BATCH_CHUNK_SIZE = 500;

    /**
     * 计算文本的 MD5 哈希值（32 位小写十六进制）
     *
     * @param text 原文
     * @return MD5 哈希字符串
     */
    private static String md5(String text) {
        try {
            var md = MessageDigest.getInstance("MD5");
            var digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return String.format("%032x", new BigInteger(1, digest));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 算法不可用", e);
        }
    }

    /** 中文字符正则 用于过滤原文已是目标语言的记录 */
    private static final java.util.regex.Pattern CHINESE_PATTERN = java.util.regex.Pattern.compile("[\\u4e00-\\u9fff]");

    /**
     * 批量查询缓存 返回每条词条的命中状态和 target_text
     * 按 BATCH_CHUNK_SIZE 分片查询 避免 SQL IN 子句过长
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

        // 构建查询参数列表
        var queryParams = request.items().stream()
                .map(item -> {
                    var param = new TranslationCache();
                    param.setRecordType(Objects.nonNull(item.recordType()) ? item.recordType() : "");
                    param.setSubrecordType(item.subrecordType());
                    param.setSourceText(item.sourceText());
                    param.setSourceTextHash(md5(item.sourceText()));
                    return param;
                })
                .collect(Collectors.toList());

        // 分片批量查询
        var hitMap = new HashMap<String, String>();
        for (var i = 0; i < queryParams.size(); i += BATCH_CHUNK_SIZE) {
            var chunk = queryParams.subList(i, Math.min(i + BATCH_CHUNK_SIZE, queryParams.size()));
            var hits = translationCacheRepository.batchQueryCache(request.targetLang(), chunk);
            hits.forEach(h -> hitMap.put(h.getRecordType() + "|" + h.getSubrecordType() + "|" + h.getSourceText(), h.getTargetText()));
        }

        // 按原始顺序构建结果
        var resultItems = request.items().stream()
                .map(item -> {
                    var key = (Objects.nonNull(item.recordType()) ? item.recordType() : "") + "|" + item.subrecordType() + "|" + item.sourceText();
                    var targetText = hitMap.get(key);
                    return Objects.nonNull(targetText)
                            ? new CacheQueryResultItem(item.recordId(), true, targetText)
                            : new CacheQueryResultItem(item.recordId(), false, null);
                })
                .collect(Collectors.toList());

        var hitCount = resultItems.stream().filter(CacheQueryResultItem::hit).count();
        log.info("[query] 查询完成 总数 {} 命中 {}", resultItems.size(), hitCount);
        return new CacheQueryResponse(resultItems);
    }

    /**
     * 批量保存翻译结果到缓存（UPSERT 语义）
     * 按 BATCH_CHUNK_SIZE 分片执行 避免单条 SQL 过长
     *
     * @param request 缓存保存请求
     */
    public void save(CacheSaveRequest request) {
        log.info("[save] 收到缓存保存请求 taskId {} targetLang {} itemsSize {}", request.taskId(), request.targetLang(), Objects.nonNull(request.items()) ? request.items().size() : 0);

        if (Objects.isNull(request.items()) || request.items().isEmpty()) {
            log.info("[save] 保存列表为空 跳过");
            return;
        }

        // 过滤掉无效缓存记录：原文含中文（已是目标语言）或原文与译文相同（未实际翻译）
        var filteredItems = request.items().stream()
                .filter(item -> Objects.nonNull(item.sourceText()) && !CHINESE_PATTERN.matcher(item.sourceText()).find())
                .filter(item -> !Objects.equals(item.sourceText(), item.targetText()))
                .collect(Collectors.toList());

        var skippedCount = request.items().size() - filteredItems.size();
        if (skippedCount > 0) {
            log.info("[save] 跳过原文含中文的记录 taskId {} skippedCount {}", request.taskId(), skippedCount);
        }

        if (filteredItems.isEmpty()) {
            log.info("[save] 过滤后无有效记录 跳过");
            return;
        }

        // 按 (recordType, subrecordType, sourceText) 去重 只保留最后一条
        var deduped = new LinkedHashMap<String, CacheSaveItem>();
        filteredItems.forEach(item -> {
            var key = (Objects.nonNull(item.recordType()) ? item.recordType() : "") + "|" + item.subrecordType() + "|" + item.sourceText();
            deduped.put(key, item);
        });

        var entities = deduped.values().stream()
                .map(item -> {
                    var entity = new TranslationCache();
                    entity.setTaskId(request.taskId());
                    entity.setRecordType(Objects.nonNull(item.recordType()) ? item.recordType() : "");
                    entity.setSubrecordType(item.subrecordType());
                    entity.setSourceText(item.sourceText());
                    entity.setSourceTextHash(md5(item.sourceText()));
                    entity.setTargetText(item.targetText());
                    entity.setTargetLang(request.targetLang());
                    return entity;
                })
                .collect(Collectors.toList());

        // 分片批量 UPSERT
        for (var i = 0; i < entities.size(); i += BATCH_CHUNK_SIZE) {
            var chunk = entities.subList(i, Math.min(i + BATCH_CHUNK_SIZE, entities.size()));
            translationCacheRepository.batchUpsertCache(chunk);
        }

        log.info("[save] 保存完成 数量 {}", entities.size());
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
}
