package com.starfield.api.dto;

/**
 * Prompt 模板创建/更新请求 DTO
 */
public record PromptRequest(
        String name,
        String content
) {}
