package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.starfield.api.dto.CreationRequest;
import com.starfield.api.entity.Creation;
import com.starfield.api.entity.CreationImage;
import com.starfield.api.entity.CreationVersion;
import com.starfield.api.repository.CreationImageRepository;
import com.starfield.api.repository.CreationRepository;
import com.starfield.api.repository.CreationVersionRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CreationServiceTest {

    @Mock
    CreationRepository creationRepository;

    @Mock
    CreationVersionRepository creationVersionRepository;

    @Mock
    CreationImageRepository creationImageRepository;

    @Mock
    CosService cosService;

    @Mock
    TranslationTaskRepository translationTaskRepository;

    @InjectMocks
    CreationService creationService;

    /** 创建作品时图片应上传到 COS 并存储 COS URL */
    @Test
    void create_withImages_uploadsToCosSavesCosUrl() {
        var request = new CreationRequest("TestMod", "测试Mod", "author", null, null, "1.0", null, null, null);
        var image = new MockMultipartFile("images", "screenshot.png", "image/png", "fake-image".getBytes());

        when(creationRepository.selectOne(any(QueryWrapper.class))).thenReturn(null);
        doAnswer(inv -> {
            var c = inv.getArgument(0, Creation.class);
            c.setId(1L);
            return 1;
        }).when(creationRepository).insert(any(Creation.class));

        when(cosService.uploadStream(any(InputStream.class), contains("creations/1/images/"), eq("image/png"), anyLong(), eq("screenshot.png")))
                .thenReturn("https://cos.example.com/creations/1/images/uuid_screenshot.png");

        // 版本创建不涉及文件
        var response = creationService.create(request, null, List.of(image));

        assertThat(response.images()).hasSize(1);
        assertThat(response.images().getFirst().url()).startsWith("https://cos.example.com/creations/1/images/");

        var imageCaptor = ArgumentCaptor.forClass(CreationImage.class);
        verify(creationImageRepository).insert(imageCaptor.capture());
        assertThat(imageCaptor.getValue().getImagePath()).startsWith("https://cos.example.com/creations/1/images/");
    }

    /** 创建作品时 Mod 文件应上传到 COS */
    @Test
    void create_withFile_uploadsFileToCos() {
        var request = new CreationRequest("TestMod", "测试Mod", "author", null, null, "1.0", null, null, null);
        var file = new MockMultipartFile("file", "mod.zip", "application/zip", "fake-mod".getBytes());

        when(creationRepository.selectOne(any(QueryWrapper.class))).thenReturn(null);
        doAnswer(inv -> {
            var c = inv.getArgument(0, Creation.class);
            c.setId(2L);
            return 1;
        }).when(creationRepository).insert(any(Creation.class));

        when(cosService.uploadStream(any(InputStream.class), contains("creations/2/files/"), eq("application/zip"), anyLong(), eq("mod.zip")))
                .thenReturn("https://cos.example.com/creations/2/files/uuid_mod.zip");

        var response = creationService.create(request, file, null);

        var versionCaptor = ArgumentCaptor.forClass(CreationVersion.class);
        verify(creationVersionRepository).insert(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getFilePath()).startsWith("https://cos.example.com/creations/2/files/");
    }

    /** 上传汉化补丁应上传到 COS 并存储 COS URL */
    @Test
    void uploadPatch_uploadsToCosSavesCosUrl() {
        var version = new CreationVersion();
        version.setId(10L);
        version.setCreationId(5L);
        version.setVersion("1.0");

        when(creationVersionRepository.selectById(10L)).thenReturn(version);
        when(cosService.uploadStream(any(InputStream.class), contains("creations/5/patches/"), eq("application/octet-stream"), anyLong(), eq("patch.esp")))
                .thenReturn("https://cos.example.com/creations/5/patches/uuid_patch.esp");

        var creation = new Creation();
        creation.setId(5L);
        creation.setName("TestMod");
        when(creationRepository.selectById(5L)).thenReturn(creation);
        when(creationVersionRepository.selectList(any(QueryWrapper.class))).thenReturn(List.of(version));
        when(creationImageRepository.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        var patchFile = new MockMultipartFile("file", "patch.esp", "application/octet-stream", "fake-patch".getBytes());
        creationService.uploadPatch(10L, patchFile);

        var captor = ArgumentCaptor.forClass(CreationVersion.class);
        verify(creationVersionRepository).updateById(captor.capture());
        assertThat(captor.getValue().getPatchFilePath()).startsWith("https://cos.example.com/creations/5/patches/");
        assertThat(captor.getValue().getPatchFileName()).isEqualTo("patch.esp");
    }

    /** 上传补丁时版本不存在应抛出异常 */
    @Test
    void uploadPatch_versionNotFound_throwsException() {
        when(creationVersionRepository.selectById(999L)).thenReturn(null);

        var patchFile = new MockMultipartFile("file", "patch.esp", "application/octet-stream", "data".getBytes());

        assertThatThrownBy(() -> creationService.uploadPatch(999L, patchFile))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("版本不存在");
    }

    /** getImageInfos 应返回 COS URL 而非代理路径 */
    @Test
    void getById_imageUrlIsCosUrl() {
        var creation = new Creation();
        creation.setId(3L);
        creation.setName("TestMod");
        when(creationRepository.selectById(3L)).thenReturn(creation);
        when(creationVersionRepository.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        var img = new CreationImage();
        img.setId(100L);
        img.setCreationId(3L);
        img.setImagePath("https://cos.example.com/creations/3/images/uuid_pic.jpg");
        img.setSortOrder(0);
        when(creationImageRepository.selectList(any(QueryWrapper.class))).thenReturn(List.of(img));

        var response = creationService.getById(3L);

        assertThat(response.images()).hasSize(1);
        assertThat(response.images().getFirst().url()).isEqualTo("https://cos.example.com/creations/3/images/uuid_pic.jpg");
        assertThat(response.images().getFirst().url()).doesNotContain("/api/creations/images/");
    }

    /** COS key 应包含 creationId 进行隔离 */
    @Test
    void create_cosKeyContainsCreationId() {
        var request = new CreationRequest("TestMod", "测试Mod", "author", null, null, "1.0", null, null, null);
        var image = new MockMultipartFile("images", "pic.jpg", "image/jpeg", "data".getBytes());

        when(creationRepository.selectOne(any(QueryWrapper.class))).thenReturn(null);
        doAnswer(inv -> {
            var c = inv.getArgument(0, Creation.class);
            c.setId(42L);
            return 1;
        }).when(creationRepository).insert(any(Creation.class));

        when(cosService.uploadStream(any(InputStream.class), anyString(), anyString(), anyLong(), anyString()))
                .thenReturn("https://cos.example.com/creations/42/images/uuid_pic.jpg");

        creationService.create(request, null, List.of(image));

        var cosKeyCaptor = ArgumentCaptor.forClass(String.class);
        verify(cosService).uploadStream(any(InputStream.class), cosKeyCaptor.capture(), anyString(), anyLong(), anyString());
        assertThat(cosKeyCaptor.getValue()).startsWith("creations/42/images/");
    }
}
