package com.starfield.api.dto;

import java.time.LocalDateTime;

/**
 * 单条确认记录响应
 */
public record ConfirmationRecordResponse(
        Long id,
        String taskId,
        String recordId,
        String recordType,
        String sourceText,
        String targetText,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
