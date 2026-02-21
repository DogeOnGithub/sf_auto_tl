package com.starfield.api.dto;

/**
 * 保存确认记录项（内部使用）
 */
public record ConfirmationSaveItem(
        String recordId,
        String recordType,
        String sourceText,
        String targetText
) {}
