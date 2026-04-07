package com.starfield.api.controller;

import com.starfield.api.dto.CreationPageResponse;
import com.starfield.api.dto.CreationRequest;
import com.starfield.api.dto.CreationResponse;
import com.starfield.api.dto.TaskResponse;
import com.starfield.api.service.CreationService;
import com.starfield.api.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Objects;

/**
 * Mod 作品管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/creations")
@RequiredArgsConstructor
public class CreationController {

    final CreationService creationService;
    final TaskService taskService;

    /**
     * 创建 Mod 作品（含首个版本，或为已有同名 mod 添加新版本）
     *
     * @param request 作品请求 JSON part
     * @param file    Mod 文件
     * @param images  图片列表
     * @return 作品响应
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreationResponse> create(
            @RequestPart("data") CreationRequest request,
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "images", required = false) List<MultipartFile> images) {
        log.info("[create] 收到创建作品请求 name {} version {}", request.name(), request.version());
        var response = creationService.create(request, file, images);
        return ResponseEntity.ok(response);
    }

    /**
     * 查询所有已使用的标签
     *
     * @return 标签列表
     */
    @GetMapping("/tags")
    public ResponseEntity<List<String>> listTags() {
        log.info("[listTags] 收到查询标签请求");
        var tags = creationService.listTags();
        return ResponseEntity.ok(tags);
    }

    /**
     * 分页查询作品列表
     *
     * @param page    页码
     * @param size    每页大小
     * @param keyword 搜索关键词
     * @return 分页响应
     */
    @GetMapping
    public ResponseEntity<CreationPageResponse> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "12") int size,
            @RequestParam(required = false) String keyword) {
        log.info("[list] 收到查询作品列表请求 page {} size {} keyword {}", page, size, keyword);
        var response = creationService.list(page, size, keyword);
        return ResponseEntity.ok(response);
    }

    /**
     * 查询作品详情
     *
     * @param id 作品 ID
     * @return 作品响应
     */
    @GetMapping("/{id}")
    public ResponseEntity<CreationResponse> getById(@PathVariable Long id) {
        log.info("[getById] 收到查询作品详情请求 id {}", id);
        var response = creationService.getById(id);
        return ResponseEntity.ok(response);
    }

    /**
     * 更新作品基本信息
     *
     * @param id      作品 ID
     * @param request 作品请求
     * @return 作品响应
     */
    @PutMapping("/{id}")
    public ResponseEntity<CreationResponse> update(
            @PathVariable Long id,
            @RequestBody CreationRequest request) {
        log.info("[update] 收到更新作品请求 id {}", id);
        var response = creationService.update(id, request);
        return ResponseEntity.ok(response);
    }

    /**
     * 删除作品（级联删除版本和图片）
     *
     * @param id 作品 ID
     * @return 204 No Content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        log.info("[delete] 收到删除作品请求 id {}", id);
        creationService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * 删除指定版本
     *
     * @param versionId 版本 ID
     * @return 204 No Content
     */
    @DeleteMapping("/versions/{versionId}")
    public ResponseEntity<Void> deleteVersion(@PathVariable Long versionId) {
        log.info("[deleteVersion] 收到删除版本请求 versionId {}", versionId);
        creationService.deleteVersion(versionId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 上传汉化补丁文件关联到指定版本
     *
     * @param versionId 版本 ID
     * @param file      补丁文件
     * @return 作品响应
     */
    @PostMapping("/versions/{versionId}/patch")
    public ResponseEntity<CreationResponse> uploadPatch(
            @PathVariable Long versionId,
            @RequestPart("file") MultipartFile file) {
        log.info("[uploadPatch] 收到上传补丁请求 versionId {}", versionId);
        var response = creationService.uploadPatch(versionId, file);
        return ResponseEntity.ok(response);
    }

    /**
     * 上传/替换 Mod 文件关联到指定版本
     *
     * @param versionId 版本 ID
     * @param file      Mod 文件
     * @return 作品响应
     */
    @PostMapping("/versions/{versionId}/file")
    public ResponseEntity<CreationResponse> uploadFile(
            @PathVariable Long versionId,
            @RequestPart("file") MultipartFile file) {
        log.info("[uploadFile] 收到上传 Mod 文件请求 versionId {}", versionId);
        var response = creationService.uploadFile(versionId, file);
        return ResponseEntity.ok(response);
    }

    /**
     * 查询作品关联的翻译任务
     *
     * @param id 作品 ID
     * @return 任务列表
     */
    @GetMapping("/{id}/tasks")
    public ResponseEntity<java.util.List<TaskResponse>> getCreationTasks(@PathVariable Long id) {
        log.info("[getCreationTasks] 查询作品关联任务 id {}", id);
        var tasks = taskService.listTasksByCreation(id);
        return ResponseEntity.ok(tasks);
    }

    /**
     * 更新版本分享链接
     *
     * @param versionId 版本 ID
     * @param body      包含 fileShareLink 的请求体
     * @return 作品响应
     */
    @PutMapping("/versions/{versionId}/share-link")
    public ResponseEntity<CreationResponse> updateVersionShareLink(
            @PathVariable Long versionId,
            @RequestBody java.util.Map<String, String> body) {
        log.info("[updateVersionShareLink] 收到更新分享链接请求 versionId {}", versionId);
        var response = creationService.updateVersionShareLink(versionId, body.get("fileShareLink"));
        return ResponseEntity.ok(response);
    }

    /**
     * 为作品添加图片
     *
     * @param id     作品 ID
     * @param images 图片文件列表
     * @return 作品响应
     */
    @PostMapping(value = "/{id}/images", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CreationResponse> addImages(
            @PathVariable Long id,
            @RequestPart("images") List<MultipartFile> images) {
        log.info("[addImages] 收到添加图片请求 id {} count {}", id, images.size());
        var response = creationService.addImages(id, images);
        return ResponseEntity.ok(response);
    }

    /**
     * 删除作品图片
     *
     * @param imageId 图片 ID
     * @return 204 No Content
     */
    @DeleteMapping("/images/{imageId}")
    public ResponseEntity<Void> deleteImage(@PathVariable Long imageId) {
        log.info("[deleteImage] 收到删除图片请求 imageId {}", imageId);
        creationService.deleteImage(imageId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 重新排序作品图片
     *
     * @param id   作品 ID
     * @param body 包含 imageIds 的请求体
     * @return 204 No Content
     */
    @PutMapping("/{id}/images/reorder")
    public ResponseEntity<Void> reorderImages(
            @PathVariable Long id,
            @RequestBody java.util.Map<String, java.util.List<Long>> body) {
        log.info("[reorderImages] 收到图片排序请求 id {}", id);
        creationService.reorderImages(id, body.get("imageIds"));
        return ResponseEntity.noContent().build();
    }

}
