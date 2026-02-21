package com.starfield.api.dto;

import java.util.List;

/**
 * 批量确认请求
 */
public record ConfirmationBatchConfirmRequest(
        List<Long> ids
) {}
