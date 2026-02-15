package com.starfield.api.dto;

/**
 * 下载响应，包含 COS 下载链接和文件名
 *
 * @param downloadUrl COS 下载链接
 * @param fileName    文件名
 */
public record DownloadResponse(String downloadUrl, String fileName) {}
