package com.starfield.api.dto;

import java.util.List;

/**
 * 创建/更新 Mod 作品请求 DTO
 */
public record CreationRequest(
        String name,
        String translatedName,
        String author,
        String ccLink,
        String nexusLink,
        String version,
        String fileShareLink,
        String remark,
        List<String> tags
) {}
