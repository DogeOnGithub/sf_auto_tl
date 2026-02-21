package com.starfield.api.dto;

import java.util.List;

/**
 * Engine 进度回调请求 DTO
 */
public record ProgressCallbackRequest(
        String taskId,
        String status,
        Progress progress,
        String outputFilePath,
        String originalBackupPath,
        String error,
        List<TranslationItem> items
) {
    /**
     * 翻译进度
     */
    public record Progress(int translated, int total) {}

    /**
     * 每批次翻译结果条目
     */
    public record TranslationItem(
            String recordId,
            String recordType,
            String sourceText,
            String targetText
    ) {}
}
