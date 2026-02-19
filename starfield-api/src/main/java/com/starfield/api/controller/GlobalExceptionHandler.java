package com.starfield.api.controller;

import com.starfield.api.client.EngineUnavailableException;
import com.starfield.api.dto.ErrorResponse;
import com.starfield.api.service.DictionaryService;
import com.starfield.api.service.DownloadService;
import com.starfield.api.service.FileUploadService;
import com.starfield.api.service.PromptService;
import com.starfield.api.service.TaskService;
import com.starfield.api.service.CreationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理无效 ESM 格式异常
     */
    @ExceptionHandler(FileUploadService.InvalidEsmFormatException.class)
    public ResponseEntity<ErrorResponse> handleInvalidEsmFormat(FileUploadService.InvalidEsmFormatException e) {
        log.warn("[handleInvalidEsmFormat] {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("INVALID_FORMAT", "文件不是有效的 ESM 格式"));
    }

    /**
     * 处理文件大小超限异常
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleMaxUploadSizeExceeded(MaxUploadSizeExceededException e) {
        log.warn("[handleMaxUploadSizeExceeded] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(new ErrorResponse("FILE_TOO_LARGE", "文件大小超过限制"));
    }

    /**
     * 处理任务不存在异常
     */
    @ExceptionHandler(TaskService.TaskNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleTaskNotFound(TaskService.TaskNotFoundException e) {
        log.warn("[handleTaskNotFound] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("TASK_NOT_FOUND", "翻译任务不存在"));
    }

    /**
     * 处理任务未完成异常
     */
    @ExceptionHandler(DownloadService.TaskNotCompletedException.class)
    public ResponseEntity<ErrorResponse> handleTaskNotCompleted(DownloadService.TaskNotCompletedException e) {
        log.warn("[handleTaskNotCompleted] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("TASK_NOT_COMPLETED", "翻译任务尚未完成"));
    }

    /**
     * 处理任务已过期异常
     */
    @ExceptionHandler(DownloadService.TaskExpiredException.class)
    public ResponseEntity<ErrorResponse> handleTaskExpired(DownloadService.TaskExpiredException e) {
        log.warn("[handleTaskExpired] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.GONE)
                .body(new ErrorResponse("TASK_EXPIRED", "翻译任务已过期 文件已清理"));
    }

    /**
     * 处理下载链接为空异常
     */
    @ExceptionHandler(DownloadService.DownloadUrlEmptyException.class)
    public ResponseEntity<ErrorResponse> handleDownloadUrlEmpty(DownloadService.DownloadUrlEmptyException e) {
        log.error("[handleDownloadUrlEmpty] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("DOWNLOAD_URL_EMPTY", "下载链接为空"));
    }


    /**
     * 处理翻译引擎不可用异常
     */
    @ExceptionHandler(EngineUnavailableException.class)
    public ResponseEntity<ErrorResponse> handleEngineUnavailable(EngineUnavailableException e) {
        log.error("[handleEngineUnavailable] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse("ENGINE_UNAVAILABLE", "翻译服务暂时不可用"));
    }

    /**
     * 处理 Prompt 模板不存在异常
     */
    @ExceptionHandler(PromptService.PromptNotFoundException.class)
    public ResponseEntity<ErrorResponse> handlePromptNotFound(PromptService.PromptNotFoundException e) {
        log.warn("[handlePromptNotFound] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("PROMPT_NOT_FOUND", e.getMessage()));
    }

    /**
     * 处理 Prompt 校验异常
     */
    @ExceptionHandler(PromptService.PromptValidationException.class)
    public ResponseEntity<ErrorResponse> handlePromptValidation(PromptService.PromptValidationException e) {
        log.warn("[handlePromptValidation] {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("PROMPT_VALIDATION_ERROR", e.getMessage()));
    }

    /**
     * 处理词条内容为空异常
     */
    @ExceptionHandler(DictionaryService.EmptyEntryException.class)
    public ResponseEntity<ErrorResponse> handleEmptyEntry(DictionaryService.EmptyEntryException e) {
        log.warn("[handleEmptyEntry] {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("EMPTY_ENTRY", "词条原文和译文不能为空"));
    }

    /**
     * 处理词条不存在异常
     */
    @ExceptionHandler(DictionaryService.EntryNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntryNotFound(DictionaryService.EntryNotFoundException e) {
        log.warn("[handleEntryNotFound] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("ENTRY_NOT_FOUND", "词条不存在"));
    }

    /**
     * 处理作品不存在异常
     */
    @ExceptionHandler(CreationService.CreationNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCreationNotFound(CreationService.CreationNotFoundException e) {
        log.warn("[handleCreationNotFound] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse("CREATION_NOT_FOUND", "作品不存在"));
    }

    /**
     * 处理版本重复异常
     */
    @ExceptionHandler(CreationService.DuplicateVersionException.class)
    public ResponseEntity<ErrorResponse> handleDuplicateVersion(CreationService.DuplicateVersionException e) {
        log.warn("[handleDuplicateVersion] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("DUPLICATE_VERSION", "该版本已存在，不能重复上传"));
    }

    /**
     * 处理任务关联 creation 不允许清理异常
     */
    @ExceptionHandler(TaskService.TaskLinkedToCreationException.class)
    public ResponseEntity<ErrorResponse> handleTaskLinkedToCreation(TaskService.TaskLinkedToCreationException e) {
        log.warn("[handleTaskLinkedToCreation] {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ErrorResponse("TASK_LINKED_TO_CREATION", "任务关联了作品，请先删除关联的版本"));
    }


}
