package com.starfield.api.controller;

import com.starfield.api.service.DownloadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 下载控制器，提供翻译文件和原始文件的下载接口
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class DownloadController {

    final DownloadService downloadService;

    /**
     * 下载翻译后文件或原始备份文件
     *
     * @param taskId 任务 ID
     * @param type   下载类型 translated（默认）或 original
     * @return 文件二进制流
     */
    @GetMapping("/{taskId}/download")
    public ResponseEntity<Resource> download(
            @PathVariable String taskId,
            @RequestParam(defaultValue = "translated") String type) {
        log.info("[download] 收到下载请求 taskId {} type {}", taskId, type);

        var result = downloadService.getDownloadFile(taskId, type);
        var encodedFileName = URLEncoder.encode(result.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedFileName)
                .body(result.resource());
    }
}
