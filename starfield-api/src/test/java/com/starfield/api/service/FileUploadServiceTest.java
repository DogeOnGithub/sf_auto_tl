package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starfield.api.client.EngineClient;
import com.starfield.api.entity.CustomPrompt;
import com.starfield.api.entity.DictionaryEntry;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.CustomPromptRepository;
import com.starfield.api.repository.DictionaryEntryRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FileUploadServiceTest {

    @Mock
    TranslationTaskRepository translationTaskRepository;

    @Mock
    CustomPromptRepository customPromptRepository;

    @Mock
    DictionaryEntryRepository dictionaryEntryRepository;

    @Mock
    EngineClient engineClient;

    @InjectMocks
    FileUploadService fileUploadService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(fileUploadService, "uploadDir", tempDir.toString());
    }

    /** 有效 ESM 文件上传应返回 taskId 和 fileName */
    @Test
    void upload_validEsmFile_returnsTaskIdAndFileName() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var response = fileUploadService.upload(file, null);

        assertThat(response.taskId()).isNotBlank();
        assertThat(response.fileName()).isEqualTo("test.esm");
        verify(translationTaskRepository).insert(any(TranslationTask.class));
    }

    /** 上传应创建 waiting 状态的任务 */
    @Test
    void upload_validFile_createsTaskWithWaitingStatus() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "mod.esm", "application/octet-stream", content);
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var captor = ArgumentCaptor.forClass(TranslationTask.class);

        fileUploadService.upload(file, null);

        verify(translationTaskRepository).insert(captor.capture());
        var savedTask = captor.getValue();
        assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.waiting);
        assertThat(savedTask.getFileName()).isEqualTo("mod.esm");
        assertThat(savedTask.getFilePath()).isNotBlank();
    }

    /** 上传应将 customPrompt 和 dictionaryEntries 传递给引擎 */
    @Test
    void upload_withPromptAndDictionary_passesToEngine() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);

        var prompt = new CustomPrompt();
        prompt.setContent("翻译为中文");
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(prompt);

        var entry = new DictionaryEntry();
        entry.setSourceText("Dragon");
        entry.setTargetText("龙");
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of(entry));

        var captor = ArgumentCaptor.forClass(EngineClient.EngineTranslateRequest.class);

        fileUploadService.upload(file, null);

        verify(engineClient).submitTranslation(captor.capture());
        var request = captor.getValue();
        assertThat(request.customPrompt()).isEqualTo("翻译为中文");
        assertThat(request.dictionaryEntries()).hasSize(1);
        assertThat(request.dictionaryEntries().getFirst().sourceText()).isEqualTo("Dragon");
        assertThat(request.dictionaryEntries().getFirst().targetText()).isEqualTo("龙");
    }

    /** 非 .esm 扩展名的文件应被拒绝 */
    @Test
    void validateEsmFormat_nonEsmExtension_throwsException() {
        var file = new MockMultipartFile("file", "test.txt", "text/plain", "hello".getBytes());

        assertThatThrownBy(() -> fileUploadService.validateEsmFormat(file))
                .isInstanceOf(FileUploadService.InvalidEsmFormatException.class);
    }

    /** 文件名为 null 应被拒绝 */
    @Test
    void validateEsmFormat_nullFileName_throwsException() {
        var file = new MockMultipartFile("file", null, "application/octet-stream", "TES4data".getBytes());

        assertThatThrownBy(() -> fileUploadService.validateEsmFormat(file))
                .isInstanceOf(FileUploadService.InvalidEsmFormatException.class);
    }

    /** .esm 扩展名但魔数字节不匹配应被拒绝 */
    @Test
    void validateEsmFormat_wrongMagicBytes_throwsException() {
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", "BADX".getBytes());

        assertThatThrownBy(() -> fileUploadService.validateEsmFormat(file))
                .isInstanceOf(FileUploadService.InvalidEsmFormatException.class);
    }

    /** 文件内容不足 4 字节应被拒绝 */
    @Test
    void validateEsmFormat_tooShortContent_throwsException() {
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", "TE".getBytes());

        assertThatThrownBy(() -> fileUploadService.validateEsmFormat(file))
                .isInstanceOf(FileUploadService.InvalidEsmFormatException.class);
    }

    /** 有效 ESM 文件应通过校验 */
    @Test
    void validateEsmFormat_validEsmFile_passes() {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);

        fileUploadService.validateEsmFormat(file);
    }

    /** 文件应存储到上传目录 */
    @Test
    void storeFile_savesFileToUploadDir() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        var taskId = "test-task-id";

        var storedPath = fileUploadService.storeFile(file, taskId);

        assertThat(storedPath).exists();
        assertThat(storedPath.getFileName().toString()).isEqualTo("test-task-id.esm");
    }

    /** 引擎提交失败不应影响上传流程 */
    @Test
    void upload_engineSubmitFails_doesNotThrow() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());
        doThrow(new RuntimeException("engine down")).when(engineClient).submitTranslation(any());

        var response = fileUploadService.upload(file, null);

        assertThat(response.taskId()).isNotBlank();
        verify(translationTaskRepository).insert(any(TranslationTask.class));
    }

    /** 每次上传应生成不同的 taskId */
    @Test
    void upload_multipleCalls_generateUniqueTaskIds() throws IOException {
        var content = createEsmContent();
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var file1 = new MockMultipartFile("file", "a.esm", "application/octet-stream", content);
        var file2 = new MockMultipartFile("file", "b.esm", "application/octet-stream", content);

        var response1 = fileUploadService.upload(file1, null);
        var response2 = fileUploadService.upload(file2, null);

        assertThat(response1.taskId()).isNotEqualTo(response2.taskId());
    }

    private byte[] createEsmContent() {
        var data = new byte[64];
        data[0] = 'T';
        data[1] = 'E';
        data[2] = 'S';
        data[3] = '4';
        return data;
    }
}
