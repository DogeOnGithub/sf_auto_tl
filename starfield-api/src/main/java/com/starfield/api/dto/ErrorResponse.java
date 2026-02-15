package com.starfield.api.dto;

/**
 * 统一错误响应 DTO
 */
public record ErrorResponse(
        String error,
        String message
) {}
