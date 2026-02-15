package com.starfield.api.dto;

/**
 * 词典词条响应 DTO
 */
public record DictionaryEntryResponse(
        Long id,
        String sourceText,
        String targetText
) {}
