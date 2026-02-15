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
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Objects;

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
