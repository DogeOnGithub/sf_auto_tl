package com.starfield.api.dto;

/**
 * 文件上传成功响应 DTO
 */
public record FileUploadResponse(
        String taskId,
        String fileName
) {}
