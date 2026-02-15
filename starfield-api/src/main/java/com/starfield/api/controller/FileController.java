package com.starfield.api.controller;

import com.starfield.api.dto.FileUploadResponse;
import com.starfield.api.service.FileUploadService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * 文件上传控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    final FileUploadService fileUploadService;

    /**
     * 上传 ESM 文件，创建翻译任务
     *
     * @param file              上传的 ESM 文件
     * @param creationVersionId 关联的 creation 版本 ID（可选）
     * @return 任务 ID 和文件名
     * @throws IOException 文件处理异常
     */
    @PostMapping("/upload")
    public ResponseEntity<FileUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "creationVersionId", required = false) Long creationVersionId) throws IOException {
        log.info("[upload] 收到文件上传请求 fileName {} creationVersionId {}", file.getOriginalFilename(), creationVersionId);
        var response = fileUploadService.upload(file, creationVersionId);
        return ResponseEntity.ok(response);
    }
}
