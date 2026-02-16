package com.starfield.api.dto;

/**
 * 缓存查询单条结果
 */
public record CacheQueryResultItem(
        String recordId,
        boolean hit,
        String targetText
) {}
