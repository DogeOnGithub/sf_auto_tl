package com.starfield.api.controller;

import com.starfield.api.dto.ConfirmationBatchConfirmRequest;
import com.starfield.api.dto.ConfirmationPageResponse;
import com.starfield.api.dto.ConfirmationRecordResponse;
import com.starfield.api.dto.ConfirmationUpdateRequest;
import com.starfield.api.service.TranslationConfirmationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 翻译确认控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/translation-confirmation")
@RequiredArgsConstructor
public class TranslationConfirmationController {

    final TranslationConfirmationService translationConfirmationService;

    /**
     * 分页查询任务的确认记录
     *
     * @param taskId  任务 ID
     * @param page    页码
     * @param size    每页大小
     * @param status  确认状态过滤（可选）
     * @param keyword 关键字搜索（可选）
     * @return 分页确认记录列表
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<ConfirmationPageResponse> listByTaskId(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        log.info("[listByTaskId] 分页查询确认记录 taskId {} page {} size {} status {} keyword {}", taskId, page, size, status, keyword);
        var response = translationConfirmationService.listByTaskId(taskId, page, size, status, keyword);
        return ResponseEntity.ok(response);
    }

    /**
     * 编辑单条确认记录的译文
     *
     * @param id      确认记录 ID
     * @param request 编辑译文请求
     * @return 更新后的确认记录
     */
    @PutMapping("/{id}")
    public ResponseEntity<ConfirmationRecordResponse> updateTargetText(
            @PathVariable Long id,
            @RequestBody ConfirmationUpdateRequest request) {
        log.info("[updateTargetText] 编辑译文 id {} targetText {}", id, request.targetText());
        var response = translationConfirmationService.updateTargetText(id, request.targetText());
        return ResponseEntity.ok(response);
    }

    /**
     * 逐条确认
     *
     * @param taskId 任务 ID
     * @param body   请求体，包含 id 字段
     * @return 200 OK
     */
    @PostMapping("/{taskId}/confirm")
    public ResponseEntity<Void> confirmSingle(
            @PathVariable String taskId,
            @RequestBody Map<String, Long> body) {
        var id = body.get("id");
        log.info("[confirmSingle] 逐条确认 taskId {} id {}", taskId, id);
        translationConfirmationService.confirmSingle(taskId, id);
        return ResponseEntity.ok().build();
    }

    /**
     * 批量确认
     *
     * @param taskId  任务 ID
     * @param request 批量确认请求
     * @return 200 OK
     */
    @PostMapping("/{taskId}/batch-confirm")
    public ResponseEntity<Void> batchConfirm(
            @PathVariable String taskId,
            @RequestBody ConfirmationBatchConfirmRequest request) {
        log.info("[batchConfirm] 批量确认 taskId {} idsSize {}", taskId, request.ids().size());
        translationConfirmationService.batchConfirm(taskId, request.ids());
        return ResponseEntity.ok().build();
    }

    /**
     * 全部确认
     *
     * @param taskId 任务 ID
     * @return 200 OK
     */
    @PostMapping("/{taskId}/confirm-all")
    public ResponseEntity<Void> confirmAll(@PathVariable String taskId) {
        log.info("[confirmAll] 全部确认 taskId {}", taskId);
        translationConfirmationService.confirmAll(taskId);
        return ResponseEntity.ok().build();
    }

    /**
     * 触发文件生成
     *
     * @param taskId 任务 ID
     * @return 200 OK
     */
    @PostMapping("/{taskId}/generate")
    public ResponseEntity<Void> generateFile(@PathVariable String taskId) {
        log.info("[generateFile] 触发文件生成 taskId {}", taskId);
        translationConfirmationService.generateFile(taskId);
        return ResponseEntity.ok().build();
    }
}
