package com.starfield.api.dto;

import java.util.List;

/**
 * 缓存批量保存请求
 */
public record CacheSaveRequest(
        String taskId,
        String targetLang,
        List<CacheSaveItem> items
) {}
