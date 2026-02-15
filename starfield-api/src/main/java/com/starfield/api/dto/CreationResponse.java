package com.starfield.api.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Mod 作品响应 DTO
 */
public record CreationResponse(
        Long id,
        String name,
        String translatedName,
        String author,
        String ccLink,
        String nexusLink,
        String remark,
        List<String> tags,
        List<VersionInfo> versions,
        List<ImageInfo> images,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
    public record VersionInfo(
            Long id,
            String version,
            String filePath,
            String fileName,
            String fileShareLink,
            String patchFilePath,
            String patchFileName,
            LocalDateTime createdAt
    ) {}

    public record ImageInfo(
            Long id,
            String url,
            int sortOrder
    ) {}
}
