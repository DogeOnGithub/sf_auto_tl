package com.starfield.api.dto;

import java.util.List;

/**
 * Mod 作品分页响应 DTO
 */
public record CreationPageResponse(
        List<CreationResponse> records,
        long total,
        long current,
        long pages
) {}
