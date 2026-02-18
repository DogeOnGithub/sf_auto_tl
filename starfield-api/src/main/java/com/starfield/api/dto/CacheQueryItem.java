package com.starfield.api.dto;

/**
 * 缓存查询单条词条
 */
public record CacheQueryItem(
        String recordId,
        String recordType,
        String subrecordType,
        String sourceText
) {}
