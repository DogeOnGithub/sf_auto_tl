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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

    @Value("${storage.upload-dir:./uploads}")
    private String uploadDir;

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

        // 检查同名 mod 是否已存在
        var existingWrapper = new QueryWrapper<Creation>().eq("name", request.name());
        var existing = creationRepository.selectOne(existingWrapper);

        if (Objects.nonNull(existing)) {
            // 同名 mod 已存在，添加新版本
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
            var filePath = saveFile(file, "creations/files");
            entity.setFilePath(filePath);
        }

        creationVersionRepository.insert(entity);
        return new CreationResponse.VersionInfo(entity.getId(), entity.getVersion(), entity.getFilePath(), entity.getFileShareLink(), entity.getPatchFilePath(), entity.getPatchFileName(), entity.getCreatedAt());
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
        var filePath = saveFile(patchFile, "creations/patches");
        version.setPatchFilePath(filePath);
        version.setPatchFileName(patchFile.getOriginalFilename());
        creationVersionRepository.updateById(version);
        return getById(version.getCreationId());
    }

    /**
     * 获取汉化补丁文件路径
     *
     * @param versionId 版本 ID
     * @return 补丁文件路径
     */
    public Path getPatchFilePath(Long versionId) {
        log.info("[getPatchFilePath] 获取补丁路径 versionId {}", versionId);
        var version = creationVersionRepository.selectById(versionId);
        if (Objects.isNull(version) || Objects.isNull(version.getPatchFilePath())) {
            throw new RuntimeException("补丁文件不存在 versionId " + versionId);
        }
        return Paths.get(version.getPatchFilePath());
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
                .map(v -> new CreationResponse.VersionInfo(v.getId(), v.getVersion(), v.getFilePath(), v.getFileShareLink(), v.getPatchFilePath(), v.getPatchFileName(), v.getCreatedAt()))
                .collect(Collectors.toList());
    }

    /**
     * 保存文件到指定子目录
     */
    private String saveFile(MultipartFile file, String subDir) {
        try {
            var dir = Paths.get(uploadDir, subDir).toAbsolutePath();
            Files.createDirectories(dir);
            var fileName = UUID.randomUUID() + "_" + file.getOriginalFilename();
            var target = dir.resolve(fileName);
            file.transferTo(target.toFile());
            return target.toString();
        } catch (IOException e) {
            log.error("[saveFile] 文件保存失败", e);
            throw new RuntimeException("文件保存失败", e);
        }
    }

    /**
     * 保存图片列表
     */
    private List<CreationResponse.ImageInfo> saveImages(Long creationId, List<MultipartFile> images) {
        if (Objects.isNull(images) || images.isEmpty()) {
            return List.of();
        }
        var result = new ArrayList<CreationResponse.ImageInfo>();
        for (int i = 0; i < images.size(); i++) {
            var img = images.get(i);
            if (img.isEmpty()) continue;
            var path = saveFile(img, "creations/images");
            var entity = new CreationImage();
            entity.setCreationId(creationId);
            entity.setImagePath(path);
            entity.setSortOrder(i);
            creationImageRepository.insert(entity);
            result.add(new CreationResponse.ImageInfo(entity.getId(), "/api/creations/images/" + entity.getId(), i));
        }
        return result;
    }

    /**
     * 获取作品图片信息列表
     */
    private List<CreationResponse.ImageInfo> getImageInfos(Long creationId) {
        var wrapper = new QueryWrapper<CreationImage>()
                .eq("creation_id", creationId)
                .orderByAsc("sort_order");
        return creationImageRepository.selectList(wrapper).stream()
                .map(img -> new CreationResponse.ImageInfo(img.getId(), "/api/creations/images/" + img.getId(), img.getSortOrder()))
                .collect(Collectors.toList());
    }

    /**
     * 根据图片 ID 获取图片文件路径
     *
     * @param imageId 图片 ID
     * @return 图片文件路径
     */
    public Path getImagePath(Long imageId) {
        log.info("[getImagePath] 获取图片路径 imageId {}", imageId);
        var image = creationImageRepository.selectById(imageId);
        if (Objects.isNull(image)) {
            throw new RuntimeException("图片不存在 imageId " + imageId);
        }
        return Paths.get(image.getImagePath());
    }

    /**
     * 转换实体为响应 DTO
     */
    private CreationResponse toResponse(Creation c, List<CreationResponse.VersionInfo> versions, List<CreationResponse.ImageInfo> images) {
        var tags = Objects.nonNull(c.getTags()) && !c.getTags().isBlank()
                ? Arrays.asList(c.getTags().split(","))
                : List.<String>of();
        return new CreationResponse(
                c.getId(), c.getName(), c.getTranslatedName(), c.getAuthor(),
                c.getCcLink(), c.getNexusLink(), c.getRemark(), tags,
                versions, images, c.getCreatedAt(), c.getUpdatedAt()
        );
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
