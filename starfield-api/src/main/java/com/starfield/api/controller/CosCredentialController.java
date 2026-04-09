package com.starfield.api.controller;

import com.starfield.api.service.CosService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * COS 直传凭证控制器，为前端分片上传提供临时密钥
 */
@Slf4j
@RestController
@RequestMapping("/api/cos")
@RequiredArgsConstructor
public class CosCredentialController {

    final CosService cosService;

    /**
     * 生成 COS 临时上传凭证，返回临时密钥和预分配的 cosKey
     *
     * @param body 请求体，包含 fileName（原始文件名）和 category（文件分类，如 files/patches）
     * @return 临时凭证 + cosKey + bucket + region
     */
    @PostMapping("/credential")
    public ResponseEntity<Map<String, Object>> getCredential(@RequestBody Map<String, String> body) {
        var fileName = body.getOrDefault("fileName", "unknown");
        var category = body.getOrDefault("category", "files");
        log.info("[getCredential] 请求临时凭证 fileName {} category {}", fileName, category);

        var cosKey = "creations/" + category + "/" + UUID.randomUUID() + "/" + fileName;
        var allowPrefix = "creations/" + category + "/*";

        var credential = cosService.generateCredential(allowPrefix);

        var result = Map.<String, Object>of(
                "cosKey", cosKey,
                "tmpSecretId", credential.tmpSecretId(),
                "tmpSecretKey", credential.tmpSecretKey(),
                "sessionToken", credential.sessionToken(),
                "startTime", credential.startTime(),
                "expiredTime", credential.expiredTime(),
                "bucket", cosService.getBucketName(),
                "region", cosService.getRegion()
        );

        return ResponseEntity.ok(result);
    }
}
