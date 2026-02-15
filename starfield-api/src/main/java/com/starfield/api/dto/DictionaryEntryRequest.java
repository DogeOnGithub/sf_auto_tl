package com.starfield.api.dto;

/**
 * 词典词条请求 DTO
 */
public record DictionaryEntryRequest(
        String sourceText,
        String targetText
) {}
