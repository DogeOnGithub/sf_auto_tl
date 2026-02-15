package com.starfield.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 腾讯云 COS 对象存储配置
 */
@ConfigurationProperties(prefix = "cos")
public record CosProperties(
    String secretId,
    String secretKey,
    String region,
    String bucketName,
    String baseUrl
) {}
