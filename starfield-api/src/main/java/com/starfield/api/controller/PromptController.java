package com.starfield.api.controller;

import com.starfield.api.dto.PromptRequest;
import com.starfield.api.dto.PromptResponse;
import com.starfield.api.service.PromptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Prompt 管理控制器，提供自定义 Prompt 的查询、保存和恢复默认接口
 */
@Slf4j
@RestController
@RequestMapping("/api/prompts")
@RequiredArgsConstructor
public class PromptController {

    final PromptService promptService;

    /**
     * 获取当前 Prompt
     *
     * @return 当前 Prompt 响应
     */
    @GetMapping("/current")
    public ResponseEntity<PromptResponse> getCurrentPrompt() {
        log.info("[getCurrentPrompt] 收到查询 Prompt 请求");
        var response = promptService.getCurrentPrompt();
        return ResponseEntity.ok(response);
    }

    /**
     * 保存自定义 Prompt
     *
     * @param request Prompt 请求
     * @return 保存后的 Prompt 响应
     */
    @PutMapping
    public ResponseEntity<PromptResponse> savePrompt(@RequestBody PromptRequest request) {
        log.info("[savePrompt] 收到保存 Prompt 请求");
        var response = promptService.savePrompt(request);
        return ResponseEntity.ok(response);
    }

    /**
     * 恢复默认 Prompt
     *
     * @return 默认 Prompt 响应
     */
    @DeleteMapping
    public ResponseEntity<PromptResponse> resetPrompt() {
        log.info("[resetPrompt] 收到恢复默认 Prompt 请求");
        var response = promptService.resetPrompt();
        return ResponseEntity.ok(response);
    }
}
