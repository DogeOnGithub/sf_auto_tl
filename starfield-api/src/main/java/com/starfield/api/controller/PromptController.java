package com.starfield.api.controller;

import com.starfield.api.dto.PromptDetailResponse;
import com.starfield.api.dto.PromptListResponse;
import com.starfield.api.dto.PromptRequest;
import com.starfield.api.service.PromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Prompt 模板管理控制器，提供多 Prompt 模板的 CRUD 接口
 */
@Slf4j
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    final PromptService promptService;

    /**
     * 创建 Prompt 模板
     *
     * @param request 包含名称和内容的请求
     * @return 创建后的 Prompt 信息
     */
    @PostMapping
    public ResponseEntity<PromptListResponse> createPrompt(@RequestBody PromptRequest request) {
        log.info("[createPrompt] 收到创建 Prompt 请求 name {}", request.name());
        var result = promptService.createPrompt(request);
        return ResponseEntity.ok(result);
    }

    /**
     * 查询 Prompt 模板列表（含使用次数）
     *
     * @return Prompt 模板列表
     */
    @GetMapping
    public ResponseEntity<List<PromptListResponse>> listPrompts() {
        log.info("[listPrompts] 收到查询 Prompt 列表请求");
        var result = promptService.listPrompts();
        return ResponseEntity.ok(result);
    }

    /**
     * 查询 Prompt 模板详情（含关联任务）
     *
     * @param id Prompt 模板 ID
     * @return Prompt 详情
     */
    @GetMapping("/{id}")
    public ResponseEntity<PromptDetailResponse> getPromptDetail(@PathVariable Long id) {
        log.info("[getPromptDetail] 收到查询 Prompt 详情请求 id {}", id);
        var result = promptService.getPromptDetail(id);
        return ResponseEntity.ok(result);
    }

    /**
     * 更新 Prompt 模板
     *
     * @param id      Prompt 模板 ID
     * @param request 包含名称和内容的请求
     * @return 更新后的 Prompt 信息
     */
    @PutMapping("/{id}")
    public ResponseEntity<PromptListResponse> updatePrompt(@PathVariable Long id, @RequestBody PromptRequest request) {
        log.info("[updatePrompt] 收到更新 Prompt 请求 id {}", id);
        var result = promptService.updatePrompt(id, request);
        return ResponseEntity.ok(result);
    }

    /**
     * 删除 Prompt 模板（软删除）
     *
     * @param id Prompt 模板 ID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePrompt(@PathVariable Long id) {
        log.info("[deletePrompt] 收到删除 Prompt 请求 id {}", id);
        promptService.deletePrompt(id);
        return ResponseEntity.noContent().build();
    }
}
