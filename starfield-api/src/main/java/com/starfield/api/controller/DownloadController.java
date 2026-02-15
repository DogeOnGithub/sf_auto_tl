package com.starfield.api.controller;

import com.starfield.api.dto.DownloadResponse;
import com.starfield.api.service.DownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * 下载控制器，返回 COS 下载链接
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class DownloadController {

    final DownloadService downloadService;

    /**
     * 获取翻译结果下载链接
     *
     * @param taskId 任务 ID
     * @return 包含 COS 下载链接和文件名的 JSON 响应
     */
    @GetMapping("/{taskId}/download")
    public ResponseEntity<DownloadResponse> download(@PathVariable String taskId) {
        log.info("[download] 收到下载请求 taskId {}", taskId);

        var result = downloadService.getDownloadFile(taskId);
        return ResponseEntity.ok(result);
    }
}
