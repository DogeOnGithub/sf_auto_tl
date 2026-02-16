package com.starfield.api.dto;

/**
 * 缓存保存单条词条
 */
public record CacheSaveItem(
        String subrecordType,
        String sourceText,
        String targetText
) {}
