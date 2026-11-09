package com.starfield.api.controller;

import com.starfield.api.dto.DictionaryEntryRequest;
import com.starfield.api.dto.DictionaryEntryResponse;
import com.starfield.api.dto.DictionaryPageResponse;
import com.starfield.api.service.DictionaryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 词典管理控制器，提供词条的增删改查接口
 */
@Slf4j
@RestController
@RequestMapping("/api/dictionary/entries")
@RequiredArgsConstructor
public class DictionaryController {

    final DictionaryService dictionaryService;

    /**
     * 分页查询词条，支持关键词搜索，按更新时间倒序
     *
     * @param keyword 搜索关键词（可选）
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @return 分页响应
     */
    @GetMapping
    public ResponseEntity<DictionaryPageResponse> getEntries(
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("[getEntries] 收到查询词条请求 keyword {} page {} size {}", keyword, page, size);
        var response = dictionaryService.getEntries(keyword, page, size);
        return ResponseEntity.ok(response);
    }

    /**
     * 创建词条，若原文已存在则更新译文
     *
     * @param request 词条请求
     * @return 词条响应
     */
    @PostMapping
    public ResponseEntity<DictionaryEntryResponse> createEntry(
            @RequestBody DictionaryEntryRequest request) {
        log.info("[createEntry] 收到创建词条请求 sourceText {}", request.sourceText());
        var response = dictionaryService.createEntry(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 更新指定 ID 的词条
     *
     * @param id      词条 ID
     * @param request 词条请求
     * @return 词条响应
     */
    @PutMapping("/{id}")
    public ResponseEntity<DictionaryEntryResponse> updateEntry(
            @PathVariable Long id,
            @RequestBody DictionaryEntryRequest request) {
        log.info("[updateEntry] 收到更新词条请求 id {}", id);
        var response = dictionaryService.updateEntry(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 删除指定 ID 的词条
     *
     * @param id 词条 ID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteEntry(@PathVariable Long id) {
        log.info("[deleteEntry] 收到删除词条请求 id {}", id);
        dictionaryService.deleteEntry(id);
        return ResponseEntity.noContent().build();
    }
}
