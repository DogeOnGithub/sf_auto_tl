package com.starfield.api.dto;

/**
 * 翻译缓存更新请求
 */
public record CacheUpdateRequest(
        String targetText
) {}
