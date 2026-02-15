package com.starfield.api.dto;

import java.util.List;

/**
 * 词典词条列表响应 DTO
 */
public record DictionaryEntriesResponse(
        List<DictionaryEntryResponse> entries
) {}
