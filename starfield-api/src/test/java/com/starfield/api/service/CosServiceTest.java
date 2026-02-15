package com.starfield.api.service;

import com.qcloud.cos.COSClient;
import com.qcloud.cos.exception.CosClientException;
import com.qcloud.cos.exception.CosServiceException;
import com.qcloud.cos.model.PutObjectRequest;
import com.qcloud.cos.model.PutObjectResult;
import com.starfield.api.config.CosProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CosServiceTest {

    @Mock
    COSClient cosClient;

    CosService cosService;

    @TempDir
    Path tempDir;

    static final String BUCKET_NAME = "test-bucket";
    static final String BASE_URL = "https://test-bucket.cos.ap-guangzhou.myqcloud.com";

    @BeforeEach
    void setUp() {
        var properties = new CosProperties("testSecretId", "testSecretKey", "ap-guangzhou", BUCKET_NAME, BASE_URL);
        cosService = new CosService(properties);
        ReflectionTestUtils.setField(cosService, "cosClient", cosClient);
    }

    /** uploadFile 应上传文件并返回正确的 COS URL */
    @Test
    void uploadFile_success_returnsCosUrl() throws IOException {
        var file = tempDir.resolve("test.esm");
        Files.writeString(file, "test content");
        var cosKey = "translations/task-1/test.zip";
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());

        var url = cosService.uploadFile(file, cosKey, "test.zip");

        assertThat(url).isEqualTo(BASE_URL + "/" + cosKey);
        verify(cosClient).putObject(any(PutObjectRequest.class));
    }

    /** uploadFile 应设置 Content-Disposition 头 */
    @Test
    void uploadFile_setsContentDisposition() throws IOException {
        var file = tempDir.resolve("test.esm");
        Files.writeString(file, "test content");
        var captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());

        cosService.uploadFile(file, "key", "我的文件.zip");

        verify(cosClient).putObject(captor.capture());
        var metadata = captor.getValue().getMetadata();
        assertThat(metadata.getContentDisposition()).contains("filename");
    }

    /** uploadFile COS 服务端异常应抛出 CosUploadException */
    @Test
    void uploadFile_cosServiceException_throwsCosUploadException() throws IOException {
        var file = tempDir.resolve("test.esm");
        Files.writeString(file, "test content");
        when(cosClient.putObject(any(PutObjectRequest.class))).thenThrow(new CosServiceException("server error"));

        assertThatThrownBy(() -> cosService.uploadFile(file, "key", "test.zip"))
                .isInstanceOf(CosService.CosUploadException.class)
                .hasCauseInstanceOf(CosServiceException.class);
    }

    /** uploadFile COS 客户端异常应抛出 CosUploadException */
    @Test
    void uploadFile_cosClientException_throwsCosUploadException() throws IOException {
        var file = tempDir.resolve("test.esm");
        Files.writeString(file, "test content");
        when(cosClient.putObject(any(PutObjectRequest.class))).thenThrow(new CosClientException("client error"));

        assertThatThrownBy(() -> cosService.uploadFile(file, "key", "test.zip"))
                .isInstanceOf(CosService.CosUploadException.class)
                .hasCauseInstanceOf(CosClientException.class);
    }

    /** uploadStream 应上传流并返回正确的 COS URL */
    @Test
    void uploadStream_success_returnsCosUrl() {
        var inputStream = new ByteArrayInputStream("stream content".getBytes());
        var cosKey = "creations/1/images/uuid_photo.jpg";
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());

        var url = cosService.uploadStream(inputStream, cosKey, "image/jpeg", 14, "photo.jpg");

        assertThat(url).isEqualTo(BASE_URL + "/" + cosKey);
        verify(cosClient).putObject(any(PutObjectRequest.class));
    }

    /** uploadStream 应设置 contentType 和 contentLength */
    @Test
    void uploadStream_setsMetadata() {
        var inputStream = new ByteArrayInputStream("data".getBytes());
        var captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        when(cosClient.putObject(any(PutObjectRequest.class))).thenReturn(new PutObjectResult());

        cosService.uploadStream(inputStream, "key", "application/zip", 100, "file.zip");

        verify(cosClient).putObject(captor.capture());
        var metadata = captor.getValue().getMetadata();
        assertThat(metadata.getContentType()).isEqualTo("application/zip");
        assertThat(metadata.getContentLength()).isEqualTo(100);
        assertThat(metadata.getContentDisposition()).contains("filename");
    }

    /** uploadStream COS 异常应抛出 CosUploadException */
    @Test
    void uploadStream_cosException_throwsCosUploadException() {
        var inputStream = new ByteArrayInputStream("data".getBytes());
        when(cosClient.putObject(any(PutObjectRequest.class))).thenThrow(new CosClientException("error"));

        assertThatThrownBy(() -> cosService.uploadStream(inputStream, "key", "text/plain", 4, "file.txt"))
                .isInstanceOf(CosService.CosUploadException.class);
    }

    /** deleteObject 应调用 cosClient 删除对象 */
    @Test
    void deleteObject_success_callsCosClient() {
        doNothing().when(cosClient).deleteObject(eq(BUCKET_NAME), eq("key-to-delete"));

        cosService.deleteObject("key-to-delete");

        verify(cosClient).deleteObject(BUCKET_NAME, "key-to-delete");
    }

    /** deleteObject COS 服务端异常应抛出 CosDeleteException */
    @Test
    void deleteObject_cosServiceException_throwsCosDeleteException() {
        doThrow(new CosServiceException("not found")).when(cosClient).deleteObject(eq(BUCKET_NAME), eq("bad-key"));

        assertThatThrownBy(() -> cosService.deleteObject("bad-key"))
                .isInstanceOf(CosService.CosDeleteException.class)
                .hasCauseInstanceOf(CosServiceException.class);
    }

    /** deleteObject COS 客户端异常应抛出 CosDeleteException */
    @Test
    void deleteObject_cosClientException_throwsCosDeleteException() {
        doThrow(new CosClientException("network error")).when(cosClient).deleteObject(eq(BUCKET_NAME), eq("bad-key"));

        assertThatThrownBy(() -> cosService.deleteObject("bad-key"))
                .isInstanceOf(CosService.CosDeleteException.class)
                .hasCauseInstanceOf(CosClientException.class);
    }
}
