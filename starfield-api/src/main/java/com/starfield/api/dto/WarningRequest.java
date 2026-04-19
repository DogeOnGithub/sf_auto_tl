package com.starfield.api.dto;

/**
 * 警告请求 DTO
 */
public record WarningRequest(
        String content,
        String status
) {}
