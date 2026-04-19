package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.starfield.api.dto.CreationPageResponse;
import com.starfield.api.dto.CreationRequest;
import com.starfield.api.dto.CreationResponse;
import com.starfield.api.dto.WarningRequest;
import com.starfield.api.entity.Creation;
import com.starfield.api.entity.CreationImage;
import com.starfield.api.entity.CreationVersion;
import com.starfield.api.repository.CreationImageRepository;
import com.starfield.api.repository.CreationRepository;
import com.starfield.api.repository.CreationVersionRepository;
import com.starfield.api.repository.CreationWarningRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import com.starfield.api.entity.CreationWarning;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.LocalDateTime;
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
    final CreationWarningRepository creationWarningRepository;
    final TranslationTaskRepository translationTaskRepository;
    final CosService cosService;

    /**
     * 查询所有已使用的标签（去重，按使用次数降序）
     *
     * @return 标签列表
     */
    public List<String> listTags() {
        log.info("[listTags] 查询所有标签");
        var creations = creationRepository.selectList(
                new QueryWrapper<Creation>().isNotNull("tags").ne("tags", "")
        );
        return creations.stream()
                .map(Creation::getTags)
                .filter(Objects::nonNull)
                .flatMap(tags -> Arrays.stream(tags.split(",")))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.groupingBy(t -> t, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

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

        // 检查是否与已有 mod 冲突（按名称、CC 链接、N 网链接匹配）
        checkDuplicateCreation(request);

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
     * 检查是否与已有 mod 冲突（按名称、CC 链接 details ID、N 网链接匹配）
     */
    private void checkDuplicateCreation(CreationRequest request) {
        // 1. 按名称匹配
        var byName = creationRepository.selectOne(new QueryWrapper<Creation>().eq("name", request.name()));
        if (Objects.nonNull(byName)) {
            throw new DuplicateCreationException(byName.getId(), byName.getName(), "名称");
        }

        // 2. 按 CC 链接 details ID 匹配（忽略 lang 和 slug 差异）
        var requestCcId = extractCcDetailsId(request.ccLink());
        if (Objects.nonNull(requestCcId)) {
            var ccCandidates = creationRepository.selectList(
                    new QueryWrapper<Creation>().isNotNull("cc_link").ne("cc_link", ""));
            var byCc = ccCandidates.stream()
                    .filter(c -> requestCcId.equals(extractCcDetailsId(c.getCcLink())))
                    .findFirst()
                    .orElse(null);
            if (Objects.nonNull(byCc)) {
                throw new DuplicateCreationException(byCc.getId(), byCc.getName(), "CC 链接");
            }
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
            if (Objects.nonNull(byNexus)) {
                throw new DuplicateCreationException(byNexus.getId(), byNexus.getName(), "Nexus 链接");
            }
        }
    }

    /**
     * 提取 CC 链接中 details 后的 ID 段作为唯一标识
     * 例如 https://creations.bethesda.net/en/starfield/details/abc123/my-mod → abc123
     */
    private String extractCcDetailsId(String ccLink) {
        if (Objects.isNull(ccLink) || ccLink.isBlank()) return null;
        try {
            var uri = java.net.URI.create(ccLink);
            var path = uri.getPath();
            if (Objects.isNull(path) || path.isBlank()) return null;
            // 查找 /details/ 后面的第一段作为 ID
            var segments = path.split("/");
            for (int i = 0; i < segments.length - 1; i++) {
                if ("details".equals(segments[i])) {
                    return segments[i + 1];
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("[extractCcDetailsId] 解析 CC 链接失败 ccLink {}", ccLink);
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
            wrapper.and(w -> w.apply("name ILIKE {0}", "%" + keyword + "%")
                    .or().apply("translated_name ILIKE {0}", "%" + keyword + "%")
                    .or().apply("author ILIKE {0}", "%" + keyword + "%")
                    .or().apply("tags ILIKE {0}", "%" + keyword + "%"));
        }

        var pageResult = creationRepository.selectPage(new Page<>(page, size), wrapper);
        var records = pageResult.getRecords().stream()
                .map(c -> toResponse(c, getVersionInfos(c.getId()), getImageInfos(c.getId())))
                .collect(Collectors.toList());

        return new CreationPageResponse(records, pageResult.getTotal(), pageResult.getCurrent(), pageResult.getPages());
    }

    /**
     * 为已有作品添加新版本
     *
     * @param creationId    作品 ID
     * @param version       版本号
     * @param fileShareLink 分享链接（可选）
     * @param file          Mod 文件（可选）
     * @return 作品响应
     */
    public CreationResponse addVersion(Long creationId, String version, String fileShareLink, MultipartFile file) {
        log.info("[addVersion] 添加版本 creationId {} version {}", creationId, version);
        var creation = creationRepository.selectById(creationId);
        if (Objects.isNull(creation)) {
            throw new CreationNotFoundException(creationId);
        }
        checkDuplicateVersion(creationId, version);
        createVersion(creationId, version, fileShareLink, file);
        return toResponse(creation, getVersionInfos(creationId), getImageInfos(creationId));
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
     * 绑定已直传 COS 的 Mod 文件到指定版本（前端分片上传后调用）
     *
     * @param versionId 版本 ID
     * @param cosKey    COS 对象键
     * @param fileName  原始文件名
     * @return 作品响应
     */
    public CreationResponse bindFile(Long versionId, String cosKey, String fileName) {
        log.info("[bindFile] 绑定 COS 文件 versionId {} cosKey {} fileName {}", versionId, cosKey, fileName);
        var version = creationVersionRepository.selectById(versionId);
        if (Objects.isNull(version)) {
            throw new RuntimeException("版本不存在 versionId " + versionId);
        }
        var cosUrl = cosService.getBaseUrl() + "/" + cosKey;
        version.setFilePath(cosUrl);
        version.setFileName(fileName);
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
     * 删除指定版本（同时清理 COS 上的 mod 文件和汉化补丁文件）
     *
     * @param versionId 版本 ID
     */
    public void deleteVersion(Long versionId) {
        log.info("[deleteVersion] 删除版本 versionId {}", versionId);
        var version = creationVersionRepository.selectById(versionId);
        if (Objects.nonNull(version)) {
            deleteCosFile(version.getFilePath());
            deleteCosFile(version.getPatchFilePath());
        }
        creationVersionRepository.deleteById(versionId);
    }

    /**
     * 为作品添加图片
     *
     * @param creationId 作品 ID
     * @param images     图片文件列表
     * @return 作品响应
     */
    public CreationResponse addImages(Long creationId, List<MultipartFile> images) {
        log.info("[addImages] 添加图片 creationId {} count {}", creationId, images.size());
        var creation = creationRepository.selectById(creationId);
        if (Objects.isNull(creation)) {
            throw new CreationNotFoundException(creationId);
        }
        saveImages(creationId, images);
        return toResponse(creation, getVersionInfos(creationId), getImageInfos(creationId));
    }

    /**
     * 删除作品图片（同时清理 COS 文件）
     *
     * @param imageId 图片 ID
     */
    public void deleteImage(Long imageId) {
        log.info("[deleteImage] 删除图片 imageId {}", imageId);
        var image = creationImageRepository.selectById(imageId);
        if (Objects.nonNull(image)) {
            deleteCosFile(image.getImagePath());
        }
        creationImageRepository.deleteById(imageId);
    }

    /**
     * 重新排序作品图片
     *
     * @param creationId 作品 ID
     * @param imageIds   按新顺序排列的图片 ID 列表
     */
    public void reorderImages(Long creationId, List<Long> imageIds) {
        log.info("[reorderImages] 重新排序图片 creationId {} imageIds {}", creationId, imageIds);
        for (int i = 0; i < imageIds.size(); i++) {
            var image = creationImageRepository.selectById(imageIds.get(i));
            if (Objects.nonNull(image) && image.getCreationId().equals(creationId)) {
                image.setSortOrder(i);
                creationImageRepository.updateById(image);
            }
        }
    }

    /**
     * 根据 COS URL 提取 cosKey 并删除对象（忽略异常）
     *
     * @param cosUrl COS 公有读 URL
     */
    private void deleteCosFile(String cosUrl) {
        if (Objects.isNull(cosUrl) || cosUrl.isBlank()) return;
        try {
            var baseUrl = cosService.getBaseUrl();
            if (cosUrl.startsWith(baseUrl + "/")) {
                var cosKey = cosUrl.substring(baseUrl.length() + 1);
                cosService.deleteObject(cosKey);
            } else {
                log.warn("[deleteCosFile] URL 不匹配 COS baseUrl cosUrl {}", cosUrl);
            }
        } catch (Exception e) {
            log.warn("[deleteCosFile] 删除 COS 文件失败 cosUrl {}", cosUrl, e);
        }
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
     * 推荐指定 Creation（FIFO 淘汰，上限 5 个）
     *
     * @param id 作品 ID
     * @return 作品响应
     */
    public CreationResponse feature(Long id) {
        log.info("[feature] 推荐作品 id {}", id);
        var creation = creationRepository.selectById(id);
        if (Objects.isNull(creation)) {
            throw new CreationNotFoundException(id);
        }

        // 幂等：已推荐则直接返回
        if (Objects.nonNull(creation.getFeatured()) && creation.getFeatured()) {
            log.info("[feature] 作品已推荐 id {}", id);
            return toResponse(creation, getVersionInfos(id), getImageInfos(id));
        }

        // FIFO 淘汰：推荐数达 5 个时淘汰 featuredAt 最早的
        var featuredWrapper = new QueryWrapper<Creation>()
                .eq("featured", true)
                .orderByAsc("featured_at");
        var featuredList = creationRepository.selectList(featuredWrapper);
        if (featuredList.size() >= 5) {
            var oldest = featuredList.get(0);
            oldest.setFeatured(false);
            oldest.setFeaturedAt(null);
            creationRepository.updateById(oldest);
            log.info("[feature] FIFO 淘汰推荐 evictedId {}", oldest.getId());
        }

        creation.setFeatured(true);
        creation.setFeaturedAt(LocalDateTime.now());
        creationRepository.updateById(creation);
        return toResponse(creation, getVersionInfos(id), getImageInfos(id));
    }

    /**
     * 取消推荐指定 Creation
     *
     * @param id 作品 ID
     * @return 作品响应
     */
    public CreationResponse unfeature(Long id) {
        log.info("[unfeature] 取消推荐作品 id {}", id);
        var creation = creationRepository.selectById(id);
        if (Objects.isNull(creation)) {
            throw new CreationNotFoundException(id);
        }
        creation.setFeatured(false);
        creation.setFeaturedAt(null);
        creationRepository.updateById(creation);
        return toResponse(creation, getVersionInfos(id), getImageInfos(id));
    }

    /**
     * 查询推荐列表（按 featuredAt 升序）
     *
     * @return 推荐作品列表
     */
    public List<CreationResponse> listFeatured() {
        log.info("[listFeatured] 查询推荐列表");
        var wrapper = new QueryWrapper<Creation>()
                .eq("featured", true)
                .orderByAsc("featured_at");
        return creationRepository.selectList(wrapper).stream()
                .map(c -> toResponse(c, getVersionInfos(c.getId()), getImageInfos(c.getId())))
                .collect(Collectors.toList());
    }

    /**
     * 为指定 Creation 添加警告记录
     *
     * @param creationId 作品 ID
     * @param request    警告请求
     * @return 作品响应
     */
    public CreationResponse addWarning(Long creationId, WarningRequest request) {
        log.info("[addWarning] 添加警告 creationId {}", creationId);
        var creation = creationRepository.selectById(creationId);
        if (Objects.isNull(creation)) {
            throw new CreationNotFoundException(creationId);
        }

        var warning = new CreationWarning();
        warning.setCreationId(creationId);
        warning.setContent(request.content());
        warning.setStatus(Objects.nonNull(request.status()) ? request.status() : "UNRESOLVED");
        creationWarningRepository.insert(warning);
        log.info("[addWarning] 警告已创建 warningId {}", warning.getId());

        return getById(creationId);
    }

    /**
     * 更新警告记录
     *
     * @param warningId 警告 ID
     * @param request   警告请求
     * @return 作品响应
     */
    public CreationResponse updateWarning(Long warningId, WarningRequest request) {
        log.info("[updateWarning] 更新警告 warningId {}", warningId);
        var warning = creationWarningRepository.selectById(warningId);
        if (Objects.isNull(warning)) {
            throw new WarningNotFoundException(warningId);
        }

        if (Objects.nonNull(request.content())) {
            warning.setContent(request.content());
        }
        if (Objects.nonNull(request.status())) {
            warning.setStatus(request.status());
        }
        creationWarningRepository.updateById(warning);
        log.info("[updateWarning] 警告已更新 warningId {}", warningId);

        return getById(warning.getCreationId());
    }

    /**
     * 删除警告记录
     *
     * @param warningId 警告 ID
     */
    public void deleteWarning(Long warningId) {
        log.info("[deleteWarning] 删除警告 warningId {}", warningId);
        var warning = creationWarningRepository.selectById(warningId);
        if (Objects.isNull(warning)) {
            throw new WarningNotFoundException(warningId);
        }
        creationWarningRepository.deleteById(warningId);
        log.info("[deleteWarning] 警告已删除 warningId {}", warningId);
    }

    /** 转换为响应 DTO */
    private CreationResponse toResponse(Creation c, List<CreationResponse.VersionInfo> versions, List<CreationResponse.ImageInfo> images) {
        var tags = Objects.nonNull(c.getTags()) && !c.getTags().isBlank()
                ? Arrays.asList(c.getTags().split(","))
                : List.<String>of();
        var hasChinesePatch = checkHasChinesePatch(c.getId(), versions);

        // 查询警告列表（按 createdAt 降序）
        var warningWrapper = new QueryWrapper<CreationWarning>()
                .eq("creation_id", c.getId())
                .orderByDesc("created_at");
        var warnings = creationWarningRepository.selectList(warningWrapper).stream()
                .map(w -> new CreationResponse.WarningInfo(w.getId(), w.getContent(), w.getStatus(), w.getCreatedAt(), w.getUpdatedAt()))
                .collect(Collectors.toList());

        return new CreationResponse(
                c.getId(), c.getName(), c.getTranslatedName(), c.getAuthor(),
                c.getCcLink(), c.getNexusLink(), c.getRemark(), tags,
                versions, images, hasChinesePatch, c.getCreatedAt(), c.getUpdatedAt(),
                Objects.nonNull(c.getFeatured()) && c.getFeatured(),
                c.getFeaturedAt(),
                warnings
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
     * 警告记录不存在异常
     */
    public static class WarningNotFoundException extends RuntimeException {
        public WarningNotFoundException(Long id) {
            super("警告记录不存在 id " + id);
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

    /**
     * 作品重复异常（名称、CC 链接或 Nexus 链接与已有作品冲突）
     */
    @Getter
    public static class DuplicateCreationException extends RuntimeException {
        private final Long existingId;
        private final String existingName;
        private final String matchType;

        public DuplicateCreationException(Long existingId, String existingName, String matchType) {
            super("作品已存在 existingId " + existingId + " existingName " + existingName + " matchType " + matchType);
            this.existingId = existingId;
            this.existingName = existingName;
            this.matchType = matchType;
        }
    }
}
