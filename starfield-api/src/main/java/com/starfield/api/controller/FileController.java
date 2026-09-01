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
     * @param promptId          选择已有 Prompt 的 ID（可选）
     * @param newPromptName     现场编写的 Prompt 名称（可选）
     * @param newPromptContent  现场编写的 Prompt 内容（可选）
     * @param confirmationMode  翻译确认模式（direct 或 confirmation，可选，默认 direct）
     * @param llmBaseUrl        自定义 LLM API 地址（可选）
     * @param llmApiKey         自定义 LLM API Key（可选，不持久化）
     * @param llmModel          自定义 LLM 模型名称（可选）
     * @param ignoreAlreadyTranslated 是否忽略「文件已汉化」的拦截（星裔专用，可选，默认 false）
     * @return 任务 ID 和文件名
     * @throws IOException 文件处理异常
     */
    @PostMapping("/upload")
    public ResponseEntity<FileUploadResponse> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "creationVersionId", required = false) Long creationVersionId,
            @RequestParam(value = "promptId", required = false) Long promptId,
            @RequestParam(value = "newPromptName", required = false) String newPromptName,
            @RequestParam(value = "newPromptContent", required = false) String newPromptContent,
            @RequestParam(value = "confirmationMode", required = false) String confirmationMode,
            @RequestParam(value = "llmBaseUrl", required = false) String llmBaseUrl,
            @RequestParam(value = "llmApiKey", required = false) String llmApiKey,
            @RequestParam(value = "llmModel", required = false) String llmModel,
            @RequestParam(value = "ignoreAlreadyTranslated", required = false, defaultValue = "false") boolean ignoreAlreadyTranslated) throws IOException {
        log.info("[upload] 收到文件上传请求 fileName {} creationVersionId {} promptId {} confirmationMode {} llmModel {} ignoreAlreadyTranslated {}", file.getOriginalFilename(), creationVersionId, promptId, confirmationMode, llmModel, ignoreAlreadyTranslated);
        var response = fileUploadService.upload(file, creationVersionId, promptId, newPromptName, newPromptContent, confirmationMode, llmBaseUrl, llmApiKey, llmModel, ignoreAlreadyTranslated);
        return ResponseEntity.ok(response);
    }

    /**
     * 上传开启本地化 mod 的 Strings 文件（前端将 strings 文件夹打包为 zip），创建翻译任务
     *
     * @param file              前端打包的 zip 文件（内含 .strings/.dlstrings/.ilstrings 三个文件）
     * @param creationVersionId 关联的 creation 版本 ID（可选）
     * @param promptId          选择已有 Prompt 的 ID（可选）
     * @param newPromptName     现场编写的 Prompt 名称（可选）
     * @param newPromptContent  现场编写的 Prompt 内容（可选）
     * @param confirmationMode  翻译确认模式（direct 或 confirmation，可选，默认 direct）
     * @param llmBaseUrl        自定义 LLM API 地址（可选）
     * @param llmApiKey         自定义 LLM API Key（可选，不持久化）
     * @param llmModel          自定义 LLM 模型名称（可选）
     * @param ignoreAlreadyTranslated 是否忽略「文件已汉化」的拦截（星裔专用，可选，默认 false）
     * @return 任务 ID 和文件基础名
     * @throws IOException 文件处理异常
     */
    @PostMapping("/upload-strings")
    public ResponseEntity<FileUploadResponse> uploadStrings(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "creationVersionId", required = false) Long creationVersionId,
            @RequestParam(value = "promptId", required = false) Long promptId,
            @RequestParam(value = "newPromptName", required = false) String newPromptName,
            @RequestParam(value = "newPromptContent", required = false) String newPromptContent,
            @RequestParam(value = "confirmationMode", required = false) String confirmationMode,
            @RequestParam(value = "llmBaseUrl", required = false) String llmBaseUrl,
            @RequestParam(value = "llmApiKey", required = false) String llmApiKey,
            @RequestParam(value = "llmModel", required = false) String llmModel,
            @RequestParam(value = "ignoreAlreadyTranslated", required = false, defaultValue = "false") boolean ignoreAlreadyTranslated) throws IOException {
        log.info("[uploadStrings] 收到 Strings 上传请求 fileName {} creationVersionId {} promptId {} confirmationMode {} llmModel {} ignoreAlreadyTranslated {}", file.getOriginalFilename(), creationVersionId, promptId, confirmationMode, llmModel, ignoreAlreadyTranslated);
        var response = fileUploadService.uploadStrings(file, creationVersionId, promptId, newPromptName, newPromptContent, confirmationMode, llmBaseUrl, llmApiKey, llmModel, ignoreAlreadyTranslated);
        return ResponseEntity.ok(response);
    }
}
