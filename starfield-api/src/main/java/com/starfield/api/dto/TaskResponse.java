package com.starfield.api.dto;

import java.time.LocalDateTime;

/**
 * 任务查询响应 DTO
 */
public record TaskResponse(
        String taskId,
        String fileName,
        String status,
        Progress progress,
        CreationInfo creation,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public record Progress(
            int translated,
            int total
    ) {}

    /**
     * 关联的 Creation 信息
     */
    public record CreationInfo(
            Long creationId,
            String name,
            String translatedName,
            Long versionId,
            String version
    ) {}
}
