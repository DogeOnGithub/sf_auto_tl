package com.starfield.api.dto;

import java.util.List;

/**
 * 翻译任务分页响应
 */
public record TaskPageResponse(
        List<TaskResponse> records,
        long total,
        long current,
        long pages
) {}
