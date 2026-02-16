package com.starfield.api.dto;

/**
 * Engine 进度回调请求 DTO
 */
public record ProgressCallbackRequest(
        String taskId,
        String status,
        Progress progress,
        String outputFilePath,
        String originalBackupPath,
        String error
) {
    /**
     * 翻译进度
     */
    public record Progress(int translated, int total) {}
}
