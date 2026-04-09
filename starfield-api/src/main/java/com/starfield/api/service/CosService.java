package com.starfield.api.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.ClientConfig;
import com.qcloud.cos.auth.BasicCOSCredentials;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.ObjectMetadata;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.region.Region;
import com.starfield.api.config.CosProperties;
import com.tencent.cloud.CosStsClient;
import com.tencent.cloud.Response;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 腾讯云 COS 对象存储服务，封装文件上传、删除等操作
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CosService {

    final CosProperties cosProperties;

    private COSClient cosClient;

    /**
     * 初始化 COSClient
     */
    @PostConstruct
    void initCosClient() {
        var credentials = new BasicCOSCredentials(cosProperties.secretId(), cosProperties.secretKey());
        var region = new Region(cosProperties.region());
        var clientConfig = new ClientConfig(region);
        cosClient = new COSClient(credentials, clientConfig);
        log.info("[initCosClient] COS 客户端初始化成功 region {}", cosProperties.region());
    }

    /**
     * 销毁 COSClient，释放资源
     */
    @PreDestroy
    void destroyCosClient() {
        if (Objects.nonNull(cosClient)) {
            cosClient.shutdown();
            log.info("[destroyCosClient] COS 客户端已关闭");
        }
    }

    /**
     * 上传本地文件到 COS，返回公有读 URL
     *
     * @param localFilePath    本地文件路径
     * @param cosKey           COS 对象键
     * @param originalFileName 原始文件名（用于 Content-Disposition）
     * @return 公有读访问 URL
     */
    public String uploadFile(Path localFilePath, String cosKey, String originalFileName) {
        log.info("[uploadFile] 开始上传文件 cosKey {} originalFileName {}", cosKey, originalFileName);
        try {
            var file = localFilePath.toFile();
            var putObjectRequest = new PutObjectRequest(cosProperties.bucketName(), cosKey, file);

            var metadata = new ObjectMetadata();
            metadata.setContentDisposition(buildContentDisposition(originalFileName));
            putObjectRequest.setMetadata(metadata);

            cosClient.putObject(putObjectRequest);
            var url = buildUrl(cosKey);
            log.info("[uploadFile] 文件上传成功 cosKey {} url {}", cosKey, url);
            return url;
        } catch (CosServiceException e) {
            log.error("[uploadFile] COS 服务端异常 cosKey {} errorCode {} errorMessage {}", cosKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new CosUploadException(cosKey, e);
        } catch (CosClientException e) {
            log.error("[uploadFile] COS 客户端异常 cosKey {}", cosKey, e);
            throw new CosUploadException(cosKey, e);
        }
    }

    /**
     * 上传 InputStream 到 COS，返回公有读 URL
     *
     * @param inputStream      输入流
     * @param cosKey           COS 对象键
     * @param contentType      内容类型
     * @param contentLength    内容长度
     * @param originalFileName 原始文件名（用于 Content-Disposition）
     * @return 公有读访问 URL
     */
    public String uploadStream(InputStream inputStream, String cosKey, String contentType, long contentLength, String originalFileName) {
        log.info("[uploadStream] 开始上传流 cosKey {} contentType {} contentLength {} originalFileName {}", cosKey, contentType, contentLength, originalFileName);
        try {
            var metadata = new ObjectMetadata();
            metadata.setContentType(contentType);
            metadata.setContentLength(contentLength);
            metadata.setContentDisposition(buildContentDisposition(originalFileName));

            var putObjectRequest = new PutObjectRequest(cosProperties.bucketName(), cosKey, inputStream, metadata);
            cosClient.putObject(putObjectRequest);
            var url = buildUrl(cosKey);
            log.info("[uploadStream] 流上传成功 cosKey {} url {}", cosKey, url);
            return url;
        } catch (CosServiceException e) {
            log.error("[uploadStream] COS 服务端异常 cosKey {} errorCode {} errorMessage {}", cosKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new CosUploadException(cosKey, e);
        } catch (CosClientException e) {
            log.error("[uploadStream] COS 客户端异常 cosKey {}", cosKey, e);
            throw new CosUploadException(cosKey, e);
        }
    }

    /**
     * 获取 COS baseUrl 配置
     *
     * @return baseUrl
     */
    public String getBaseUrl() {
        return cosProperties.baseUrl();
    }

    /**
     * 获取 bucket 名称
     *
     * @return bucketName
     */
    public String getBucketName() {
        return cosProperties.bucketName();
    }

    /**
     * 获取 region
     *
     * @return region
     */
    public String getRegion() {
        return cosProperties.region();
    }

    /**
     * 生成 COS 临时上传凭证（STS），限定指定 cosKey 前缀的上传权限
     *
     * @param allowPrefix 允许上传的 cosKey 前缀，如 "creations/123/files/*"
     * @return 临时凭证信息
     */
    public CosCredential generateCredential(String allowPrefix) {
        log.info("[generateCredential] 生成临时凭证 allowPrefix {}", allowPrefix);
        try {
            var config = new TreeMap<String, Object>();
            config.put("secretId", cosProperties.secretId());
            config.put("secretKey", cosProperties.secretKey());
            config.put("durationSeconds", 1800);
            config.put("bucket", cosProperties.bucketName());
            config.put("region", cosProperties.region());
            config.put("allowPrefix", allowPrefix);
            config.put("allowActions", new String[]{
                    "name/cos:PutObject",
                    "name/cos:PostObject",
                    "name/cos:InitiateMultipartUpload",
                    "name/cos:ListMultipartUploads",
                    "name/cos:ListParts",
                    "name/cos:UploadPart",
                    "name/cos:CompleteMultipartUpload",
                    "name/cos:AbortMultipartUpload"
            });

            var response = CosStsClient.getCredential(config);
            log.info("[generateCredential] 临时凭证生成成功");
            return new CosCredential(
                    response.credentials.tmpSecretId,
                    response.credentials.tmpSecretKey,
                    response.credentials.sessionToken,
                    response.startTime,
                    response.expiredTime
            );
        } catch (Exception e) {
            log.error("[generateCredential] 生成临时凭证失败", e);
            throw new RuntimeException("生成 COS 临时凭证失败", e);
        }
    }

    /**
     * COS 临时上传凭证
     */
    public record CosCredential(
            String tmpSecretId,
            String tmpSecretKey,
            String sessionToken,
            long startTime,
            long expiredTime
    ) {}

    /**
     * 删除 COS 对象
     *
     * @param cosKey COS 对象键
     */
    public void deleteObject(String cosKey) {
        log.info("[deleteObject] 删除 COS 对象 cosKey {}", cosKey);
        try {
            cosClient.deleteObject(cosProperties.bucketName(), cosKey);
            log.info("[deleteObject] COS 对象删除成功 cosKey {}", cosKey);
        } catch (CosServiceException e) {
            log.error("[deleteObject] COS 服务端异常 cosKey {} errorCode {} errorMessage {}", cosKey, e.getErrorCode(), e.getErrorMessage(), e);
            throw new CosDeleteException(cosKey, e);
        } catch (CosClientException e) {
            log.error("[deleteObject] COS 客户端异常 cosKey {}", cosKey, e);
            throw new CosDeleteException(cosKey, e);
        }
    }

    /**
     * 根据 cosKey 生成公有读 URL
     *
     * @param cosKey COS 对象键
     * @return 公有读 URL
     */
    private String buildUrl(String cosKey) {
        return cosProperties.baseUrl() + "/" + cosKey;
    }

    /**
     * 构建 Content-Disposition 头，对文件名进行 URL 编码
     *
     * @param originalFileName 原始文件名
     * @return Content-Disposition 值
     */
    private String buildContentDisposition(String originalFileName) {
        var encodedFileName = URLEncoder.encode(originalFileName, StandardCharsets.UTF_8).replace("+", "%20");
        return "attachment; filename=\"" + encodedFileName + "\"; filename*=UTF-8''" + encodedFileName;
    }

    /**
     * COS 上传异常
     */
    public static class CosUploadException extends RuntimeException {
        public CosUploadException(String cosKey, Throwable cause) {
            super("COS 上传失败 cosKey " + cosKey, cause);
        }
    }

    /**
     * COS 删除异常
     */
    public static class CosDeleteException extends RuntimeException {
        public CosDeleteException(String cosKey, Throwable cause) {
            super("COS 删除失败 cosKey " + cosKey, cause);
        }
    }
}
