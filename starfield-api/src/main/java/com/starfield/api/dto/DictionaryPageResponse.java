package com.starfield.api.dto;

import java.util.List;

/**
 * 词典词条分页响应，结构与翻译缓存分页保持一致，前端可复用同一套分页组件
 *
 * @param records 当前页词条
 * @param total   符合条件的词条总数
 * @param current 当前页码，从 1 开始
 * @param pages   总页数
 */
public record DictionaryPageResponse(
        List<DictionaryEntryResponse> records,
        long total,
        long current,
        long pages
) {}
