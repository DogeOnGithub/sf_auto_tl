package com.starfield.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Prompt 模板详情响应 DTO（含关联任务列表）
 */
public record PromptDetailResponse(
        Long id,
        String name,
        String content,
        Integer usageCount,
        List<TaskBriefInfo> tasks,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    /**
     * 关联任务简要信息
     */
    public record TaskBriefInfo(
            String taskId,
            String fileName,
            String status,
            LocalDateTime createdAt
    ) {}
}
