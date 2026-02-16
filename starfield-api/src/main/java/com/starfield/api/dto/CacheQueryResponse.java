package com.starfield.api.dto;

import java.util.List;

/**
 * 缓存批量查询响应
 */
public record CacheQueryResponse(
        List<CacheQueryResultItem> items
) {}
