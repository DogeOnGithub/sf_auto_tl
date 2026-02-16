package com.starfield.api.dto;

import java.time.LocalDateTime;

/**
 * 翻译缓存单条记录响应
 */
public record CacheEntryResponse(
        Long id,
        String taskId,
        String subrecordType,
        String sourceText,
        String targetText,
        String targetLang,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
