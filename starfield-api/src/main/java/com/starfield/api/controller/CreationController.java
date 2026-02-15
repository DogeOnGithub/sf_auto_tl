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
     * 下载汉化补丁文件
     *
     * @param versionId 版本 ID
     * @return 补丁文件
     */
    @GetMapping("/versions/{versionId}/patch")
    public ResponseEntity<Resource> downloadPatch(@PathVariable Long versionId) {
        log.info("[downloadPatch] 下载补丁 versionId {}", versionId);
        var path = creationService.getPatchFilePath(versionId);
        var resource = new FileSystemResource(path);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        var fileName = path.getFileName().toString();
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"" + fileName + "\"")
                .body(resource);
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
     * 获取图片资源
     *
     * @param imageId 图片 ID
     * @return 图片文件
     */
    @GetMapping("/images/{imageId}")
    public ResponseEntity<Resource> getImage(@PathVariable Long imageId) {
        log.info("[getImage] 获取图片 imageId {}", imageId);
        var path = creationService.getImagePath(imageId);
        var resource = new FileSystemResource(path);
        if (!resource.exists()) {
            return ResponseEntity.notFound().build();
        }
        var contentType = Objects.nonNull(path.getFileName()) && path.getFileName().toString().endsWith(".png")
                ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(contentType).body(resource);
    }
}
