package com.starfield.api.dto;

import java.util.List;

/**
 * 确认记录分页响应
 */
public record ConfirmationPageResponse(
        List<ConfirmationRecordResponse> records,
        long total,
        long current,
        long pages
) {}
