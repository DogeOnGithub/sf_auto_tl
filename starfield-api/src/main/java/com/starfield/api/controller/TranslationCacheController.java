package com.starfield.api.controller;

import com.starfield.api.dto.*;
import com.starfield.api.service.TranslationCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 翻译缓存控制器，提供缓存查询、保存、列表和编辑接口
 */
@Slf4j
@RestController
@RequestMapping("/api/translation-cache")
@RequiredArgsConstructor
public class TranslationCacheController {

    final TranslationCacheService translationCacheService;

    /**
     * 分页查询缓存列表
     *
     * @param page    页码
     * @param size    每页大小
     * @param keyword 搜索关键词
     * @return 分页响应
     */
    @GetMapping("/list")
    public ResponseEntity<CachePageResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String keyword) {
        var response = translationCacheService.list(page, size, keyword);
        return ResponseEntity.ok(response);
    }

    /**
     * 更新缓存记录的译文
     *
     * @param id      缓存记录 ID
     * @param request 更新请求
     * @return 更新后的记录
     */
    @PutMapping("/{id}")
    public ResponseEntity<CacheEntryResponse> update(
            @PathVariable Long id,
            @RequestBody CacheUpdateRequest request) {
        var response = translationCacheService.update(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 删除缓存记录
     *
     * @param id 缓存记录 ID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        translationCacheService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 批量删除缓存记录
     *
     * @param ids 缓存记录 ID 列表
     * @return 204 No Content
     */
    @DeleteMapping("/batch")
    public ResponseEntity<Void> batchDelete(@RequestBody java.util.List<Long> ids) {
        translationCacheService.batchDelete(ids);
        return ResponseEntity.noContent().build();
    }

    /**
     * 批量查询翻译缓存
     *
     * @param request 缓存查询请求
     * @return 缓存查询响应
     */
    @PostMapping("/query")
    public ResponseEntity<CacheQueryResponse> query(@RequestBody CacheQueryRequest request) {
        log.info("[query] 收到缓存查询请求 targetLang {}", request.targetLang());
        var response = translationCacheService.query(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 批量保存翻译缓存
     *
     * @param request 缓存保存请求
     * @return 200 OK
     */
    @PostMapping("/save")
    public ResponseEntity<Void> save(@RequestBody CacheSaveRequest request) {
        log.info("[save] 收到缓存保存请求 taskId {}", request.taskId());
        translationCacheService.save(request);
        return ResponseEntity.ok().build();
    }
}
