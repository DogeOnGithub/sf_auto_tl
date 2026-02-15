package com.starfield.api.dto;

import java.time.LocalDateTime;

/**
 * Prompt 模板列表响应 DTO（含使用次数）
 */
public record PromptListResponse(
        Long id,
        String name,
        String content,
        Integer usageCount,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
