package com.starfield.api.dto;

/**
 * Prompt 查询响应 DTO
 */
public record PromptResponse(
        String content,
        boolean isCustom
) {}
