package com.starfield.api.dto;

import java.util.List;

/**
 * 翻译缓存分页响应
 */
public record CachePageResponse(
        List<CacheEntryResponse> records,
        long total,
        long current,
        long pages
) {}
