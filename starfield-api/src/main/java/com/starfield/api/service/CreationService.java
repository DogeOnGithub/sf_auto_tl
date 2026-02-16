package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.starfield.api.dto.CreationPageResponse;
import com.starfield.api.dto.CreationRequest;
import com.starfield.api.dto.CreationResponse;
import com.starfield.api.entity.Creation;
import com.starfield.api.entity.CreationImage;
import com.starfield.api.entity.CreationVersion;
import com.starfield.api.repository.CreationImageRepository;
import com.starfield.api.repository.CreationRepository;
import com.starfield.api.repository.CreationVersionRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Mod 作品服务
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CreationService {

    final CreationRepository creationRepository;
    final CreationVersionRepository creationVersionRepository;
    final CreationImageRepository creationImageRepository;
    final TranslationTaskRepository translationTaskRepository;
    final CosService cosService;

    /**
     * 创建 Mod 作品（含首个版本）
     *
     * @param request 作品请求
     * @param file    Mod 文件（可选）
     * @param images  图片文件列表（可选）
     * @return 作品响应
     */
    public CreationResponse create(CreationRequest request, MultipartFile file, List<MultipartFile> images) {
        log.info("[create] 创建作品 name {} version {}", request.name(), request.version());

        // 查找已存在的同一 mod（按名称、CC 链接、N 网链接匹配）
        var existing = findExistingCreation(request);

        if (Objects.nonNull(existing)) {
            log.info("[create] 匹配到已有作品 id {} name {}", existing.getId(), existing.getName());
            return addVersion(existing, request, file, images);
        }

        var creation = new Creation();
        creation.setName(request.name());
        creation.setTranslatedName(request.translatedName());
        creation.setAuthor(request.author());
        creation.setCcLink(request.ccLink());
        creation.setNexusLink(request.nexusLink());
        creation.setRemark(request.remark());
        creation.setTags(Objects.nonNull(request.tags()) ? String.join(",", request.tags()) : null);
        creationRepository.insert(creation);
        log.info("[create] 作品已创建 id {}", creation.getId());

        // 创建版本
        var versionInfo = createVersion(creation.getId(), request.version(), request.fileShareLink(), file);

        // 保存图片
        var imageInfos = saveImages(creation.getId(), images);

        return toResponse(creation, List.of(versionInfo), imageInfos);
    }

    /**
     * 查找已存在的同一 mod（按名称、CC 链接最后一段、N 网链接匹配）
     */
    private Creation findExistingCreation(CreationRequest request) {
        // 1. 按名称匹配
        var byName = creationRepository.selectOne(new QueryWrapper<Creation>().eq("name", request.name()));
        if (Objects.nonNull(byName)) return byName;

        // 2. 按 CC 链接最后一段路径匹配（忽略 lang 参数等差异）
        var requestCcSlug = extractCcSlug(request.ccLink());
        if (Objects.nonNull(requestCcSlug)) {
            var ccCandidates = creationRepository.selectList(
                    new QueryWrapper<Creation>().isNotNull("cc_link").ne("cc_link", ""));
            var byCc = ccCandidates.stream()
                    .filter(c -> requestCcSlug.equals(extractCcSlug(c.getCcLink())))
                    .findFirst()
                    .orElse(null);
            if (Objects.nonNull(byCc)) return byCc;
        }

        // 3. 按 N 网链接匹配（提取路径部分比较，忽略 query 参数）
        var requestNexusPath = extractUrlPath(request.nexusLink());
        if (Objects.nonNull(requestNexusPath)) {
            var nexusCandidates = creationRepository.selectList(
                    new QueryWrapper<Creation>().isNotNull("nexus_link").ne("nexus_link", ""));
            var byNexus = nexusCandidates.stream()
                    .filter(c -> requestNexusPath.equals(extractUrlPath(c.getNexusLink())))
                    .findFirst()
                    .orElse(null);
            if (Objects.nonNull(byNexus)) return byNexus;
        }

        return null;
    }

    /**
     * 提取 CC 链接的最后一段路径作为标识（忽略 lang 等路径参数差异）
     * 例如 https://creations.bethesda.net/en/starfield/details/xxx/name → name
     */
    private String extractCcSlug(String ccLink) {
        if (Objects.isNull(ccLink) || ccLink.isBlank()) return null;
        try {
            var uri = java.net.URI.create(ccLink);
            var path = uri.getPath();
            if (Objects.isNull(path) || path.isBlank()) return null;
            // 去掉末尾斜杠后取最后一段
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            var lastSlash = path.lastIndexOf('/');
            return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        } catch (Exception e) {
            log.warn("[extractCcSlug] 解析 CC 链接失败 ccLink {}", ccLink);
            return null;
        }
    }

    /**
     * 提取 URL 的路径部分（去掉 query 和 fragment）
     */
    private String extractUrlPath(String url) {
        if (Objects.isNull(url) || url.isBlank()) return null;
        try {
            var uri = java.net.URI.create(url);
            var path = uri.getPath();
            if (Objects.isNull(path) || path.isBlank()) return null;
            if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
            return uri.getHost() + path;
        } catch (Exception e) {
            log.warn("[extractUrlPath] 解析 URL 失败 url {}", url);
            return null;
        }
    }

    /**
     * 为已有作品添加新版本
     */
    private CreationResponse addVersion(Creation creation, CreationRequest request, MultipartFile file, List<MultipartFile> images) {
        log.info("[addVersion] 为作品添加版本 creationId {} version {}", creation.getId(), request.version());

        // 检查版本是否重复
        checkDuplicateVersion(creation.getId(), request.version());

        var versionInfo = createVersion(creation.getId(), request.version(), request.fileShareLink(), file);

        // 如果有新图片也保存
        saveImages(creation.getId(), images);

        return toResponse(creation, getVersionInfos(creation.getId()), getImageInfos(creation.getId()));
    }

    /**
     * 检查版本是否重复
     */
    private void checkDuplicateVersion(Long creationId, String version) {
        if (Objects.isNull(version) || version.isBlank()) return;
        var wrapper = new QueryWrapper<CreationVersion>()
                .eq("creation_id", creationId)
                .eq("version", version);
        var count = creationVersionRepository.selectCount(wrapper);
        if (count > 0) {
            throw new DuplicateVersionException(creationId, version);
        }
    }

    /**
     * 创建版本记录
     */
    private CreationResponse.VersionInfo createVersion(Long creationId, String version, String fileShareLink, MultipartFile file) {
        var entity = new CreationVersion();
        entity.setCreationId(creationId);
        entity.setVersion(Objects.nonNull(version) ? version : "1.0");
        entity.setFileShareLink(fileShareLink);

        if (Objects.nonNull(file) && !file.isEmpty()) {
            var filePath = saveFile(file, creationId, "files");
            entity.setFilePath(filePath);
            entity.setFileName(file.getOriginalFilename());
        }

        creationVersionRepository.insert(entity);
        return new CreationResponse.VersionInfo(entity.getId(), entity.getVersion(), entity.getFilePath(), entity.getFileName(), entity.getFileShareLink(), entity.getPatchFilePath(), entity.getPatchFileName(), entity.getCreatedAt());
    }

    /**
     * 分页查询作品列表
     *
     * @param page    页码
     * @param size    每页大小
     * @param keyword 搜索关键词（可选）
     * @return 分页响应
     */
    public CreationPageResponse list(int page, int size, String keyword) {
        log.info("[list] 查询作品列表 page {} size {} keyword {}", page, size, keyword);

        var wrapper = new QueryWrapper<Creation>().orderByDesc("created_at");
        if (Objects.nonNull(keyword) && !keyword.isBlank()) {
            wrapper.and(w -> w.like("name", keyword)
                    .or().like("translated_name", keyword)
                    .or().like("author", keyword)
                    .or().like("tags", keyword));
        }

        var pageResult = creationRepository.selectPage(new Page<>(page, size), wrapper);
        var records = pageResult.getRecords().stream()
                .map(c -> toResponse(c, getVersionInfos(c.getId()), getImageInfos(c.getId())))
                .collect(Collectors.toList());

        return new CreationPageResponse(records, pageResult.getTotal(), pageResult.getCurrent(), pageResult.getPages());
    }

    /**
     * 查询作品详情
     *
     * @param id 作品 ID
     * @return 作品响应
     */
    public CreationResponse getById(Long id) {
        log.info("[getById] 查询作品详情 id {}", id);
        var creation = creationRepository.selectById(id);
        if (Objects.isNull(creation)) {
            throw new CreationNotFoundException(id);
        }
        return toResponse(creation, getVersionInfos(id), getImageInfos(id));
    }

    /**
     * 更新作品基本信息
     *
     * @param id      作品 ID
     * @param request 作品请求
     * @return 作品响应
     */
    public CreationResponse update(Long id, CreationRequest request) {
        log.info("[update] 更新作品 id {}", id);
        var creation = creationRepository.selectById(id);
        if (Objects.isNull(creation)) {
            throw new CreationNotFoundException(id);
        }

        creation.setName(request.name());
        creation.setTranslatedName(request.translatedName());
        creation.setAuthor(request.author());
        creation.setCcLink(request.ccLink());
        creation.setNexusLink(request.nexusLink());
        creation.setRemark(request.remark());
        creation.setTags(Objects.nonNull(request.tags()) ? String.join(",", request.tags()) : null);

        creationRepository.updateById(creation);
        return toResponse(creation, getVersionInfos(id), getImageInfos(id));
    }

    /**
     * 软删除作品（级联软删除关联版本和图片）
     *
     * @param id 作品 ID
     */
    public void delete(Long id) {
        log.info("[delete] 软删除作品 id {}", id);
        var creation = creationRepository.selectById(id);
        if (Objects.isNull(creation)) {
            throw new CreationNotFoundException(id);
        }
        // @TableLogic 会自动将 delete 转为 UPDATE SET deleted=true
        creationVersionRepository.delete(new QueryWrapper<CreationVersion>().eq("creation_id", id));
        creationImageRepository.delete(new QueryWrapper<CreationImage>().eq("creation_id", id));
        creationRepository.deleteById(id);
    }

    /**
     * 上传汉化补丁文件并关联到指定版本
     *
     * @param versionId 版本 ID
     * @param patchFile 汉化补丁文件
     * @return 作品响应
     */
    public CreationResponse uploadPatch(Long versionId, MultipartFile patchFile) {
        log.info("[uploadPatch] 上传汉化补丁 versionId {}", versionId);
        var version = creationVersionRepository.selectById(versionId);
        if (Objects.isNull(version)) {
            throw new RuntimeException("版本不存在 versionId " + versionId);
        }
        var cosUrl = saveFile(patchFile, version.getCreationId(), "patches");
        version.setPatchFilePath(cosUrl);
        version.setPatchFileName(patchFile.getOriginalFilename());
        creationVersionRepository.updateById(version);
        return getById(version.getCreationId());
    }

    /**
     * 上传/替换 Mod 文件并关联到指定版本
     *
     * @param versionId 版本 ID
     * @param file      Mod 文件
     * @return 作品响应
     */
    public CreationResponse uploadFile(Long versionId, MultipartFile file) {
        log.info("[uploadFile] 上传 Mod 文件 versionId {}", versionId);
        var version = creationVersionRepository.selectById(versionId);
        if (Objects.isNull(version)) {
            throw new RuntimeException("版本不存在 versionId " + versionId);
        }
        var cosUrl = saveFile(file, version.getCreationId(), "files");
        version.setFilePath(cosUrl);
        version.setFileName(file.getOriginalFilename());
        creationVersionRepository.updateById(version);
        return getById(version.getCreationId());
    }

    /**
     * 更新版本分享链接
     *
     * @param versionId     版本 ID
     * @param fileShareLink 分享链接
     * @return 作品响应
     */
    public CreationResponse updateVersionShareLink(Long versionId, String fileShareLink) {
        log.info("[updateVersionShareLink] 更新分享链接 versionId {} fileShareLink {}", versionId, fileShareLink);
        var version = creationVersionRepository.selectById(versionId);
        if (Objects.isNull(version)) {
            throw new RuntimeException("版本不存在 versionId " + versionId);
        }
        version.setFileShareLink(fileShareLink);
        creationVersionRepository.updateById(version);
        return getById(version.getCreationId());
    }

    /**
     * 删除指定版本
     *
     * @param versionId 版本 ID
     */
    public void deleteVersion(Long versionId) {
        log.info("[deleteVersion] 删除版本 versionId {}", versionId);
        creationVersionRepository.deleteById(versionId);
    }

    /**
     * 获取作品版本列表
     */
    private List<CreationResponse.VersionInfo> getVersionInfos(Long creationId) {
        var wrapper = new QueryWrapper<CreationVersion>()
                .eq("creation_id", creationId)
                .orderByDesc("created_at");
        return creationVersionRepository.selectList(wrapper).stream()
                .map(v -> new CreationResponse.VersionInfo(v.getId(), v.getVersion(), v.getFilePath(), v.getFileName(), v.getFileShareLink(), v.getPatchFilePath(), v.getPatchFileName(), v.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * 保存文件到 COS
     *
     * @param file       上传的文件
     * @param creationId 作品 ID（用于 COS key 隔离）
     * @param category   文件分类（images/patches/files）
     * @return COS 公有读 URL
     */
    private String saveFile(MultipartFile file, Long creationId, String category) {
        try {
            var originalName = Objects.nonNull(file.getOriginalFilename()) ? file.getOriginalFilename() : "unknown";
            var cosKey = "creations/" + creationId + "/" + category + "/" + UUID.randomUUID() + "_" + originalName;
            var contentType = Objects.nonNull(file.getContentType()) ? file.getContentType() : "application/octet-stream";
            return cosService.uploadStream(file.getInputStream(), cosKey, contentType, file.getSize(), originalName);
        } catch (IOException e) {
            log.error("[saveFile] 文件上传失败 creationId {} category {}", creationId, category, e);
            throw new RuntimeException("文件上传失败", e);
        }
    }

    /**
     * 保存图片列表到 COS
     *
     * @param creationId 作品 ID
     * @param images     图片文件列表
     * @return 图片信息列表
     */
    private List<CreationResponse.ImageInfo> saveImages(Long creationId, List<MultipartFile> images) {
        if (Objects.isNull(images) || images.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<CreationResponse.ImageInfo>();
        for (int i = 0; i < images.size(); i++) {
            var img = images.get(i);
            if (img.isEmpty()) continue;
            var cosUrl = saveFile(img, creationId, "images");
            var entity = new CreationImage();
            entity.setCreationId(creationId);
            entity.setImagePath(cosUrl);
            entity.setSortOrder(i);
            creationImageRepository.insert(entity);
            result.add(new CreationResponse.ImageInfo(entity.getId(), cosUrl, i));
        }
        return result;
    }

    /**
     * 获取作品图片信息列表（URL 为 COS 公有读地址）
     *
     * @param creationId 作品 ID
     * @return 图片信息列表
     */
    private List<CreationResponse.ImageInfo> getImageInfos(Long creationId) {
        var wrapper = new QueryWrapper<CreationImage>()
                .eq("creation_id", creationId)
                .orderByAsc("sort_order");
        return creationImageRepository.selectList(wrapper).stream()
                .map(img -> new CreationResponse.ImageInfo(img.getId(), img.getImagePath(), img.getSortOrder()))
                .collect(Collectors.toList());
    }

    /**
     * 转换实体为响应 DTO
     */
    /** 转换为响应 DTO */
    private CreationResponse toResponse(Creation c, List<CreationResponse.VersionInfo> versions, List<CreationResponse.ImageInfo> images) {
        var tags = Objects.nonNull(c.getTags()) && !c.getTags().isBlank()
                ? Arrays.asList(c.getTags().split(","))
                : List.<String>of();
        var hasChinesePatch = checkHasChinesePatch(c.getId(), versions);
        return new CreationResponse(
                c.getId(), c.getName(), c.getTranslatedName(), c.getAuthor(),
                c.getCcLink(), c.getNexusLink(), c.getRemark(), tags,
                versions, images, hasChinesePatch, c.getCreatedAt(), c.getUpdatedAt()
        );
    }

    /** 判断是否有简体中文补丁（任意版本有 patchFilePath 或有已完成的翻译任务） */
    private boolean checkHasChinesePatch(Long creationId, List<CreationResponse.VersionInfo> versions) {
        var hasPatch = versions.stream()
                .anyMatch(v -> Objects.nonNull(v.patchFilePath()) && !v.patchFilePath().isBlank());
        if (hasPatch) {
            return true;
        }
        var versionIds = versions.stream()
                .map(CreationResponse.VersionInfo::id)
                .collect(Collectors.toList());
        if (versionIds.isEmpty()) {
            return false;
        }
        var taskWrapper = new QueryWrapper<TranslationTask>()
                .in("creation_version_id", versionIds)
                .eq("status", TaskStatus.completed.name())
                .last("LIMIT 1");
        return translationTaskRepository.selectCount(taskWrapper) > 0;
    }

    /**
     * 作品不存在异常
     */
    public static class CreationNotFoundException extends RuntimeException {
        public CreationNotFoundException(Long id) {
            super("作品不存在 id " + id);
        }
    }

    /**
     * 版本重复异常
     */
    public static class DuplicateVersionException extends RuntimeException {
        public DuplicateVersionException(Long creationId, String version) {
            super("版本已存在 creationId " + creationId + " version " + version);
        }
    }
}
