package com.starfield.api.dto;

import java.util.List;

/**
 * 缓存批量查询请求
 */
public record CacheQueryRequest(
        String targetLang,
        List<CacheQueryItem> items
) {}
