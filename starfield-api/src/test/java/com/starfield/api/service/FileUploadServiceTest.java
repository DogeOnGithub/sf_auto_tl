package com.starfield.api.service;

import com.starfield.api.client.EngineClient;
import com.starfield.api.dto.PromptListResponse;
import com.starfield.api.dto.PromptRequest;
import com.starfield.api.entity.DictionaryEntry;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
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
import java.time.LocalDateTime;
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
    PromptService promptService;

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
        ReflectionTestUtils.setField(fileUploadService, "apiBaseUrl", "http://localhost:8080");
    }

    /** 有效 ESM 文件上传（默认 Prompt）应返回 taskId 和 fileName */
    @Test
    void upload_validEsmFile_returnsTaskIdAndFileName() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var response = fileUploadService.upload(file, null, null, null, null);

        assertThat(response.taskId()).isNotBlank();
        assertThat(response.fileName()).isEqualTo("test.esm");
        verify(translationTaskRepository).insert(any(TranslationTask.class));
    }

    /** 上传应创建 waiting 状态的任务，默认 Prompt 时 promptId 为 null */
    @Test
    void upload_defaultPrompt_createsTaskWithNullPromptId() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "mod.esm", "application/octet-stream", content);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var captor = ArgumentCaptor.forClass(TranslationTask.class);

        fileUploadService.upload(file, null, null, null, null);

        verify(translationTaskRepository).insert(captor.capture());
        var savedTask = captor.getValue();
        assertThat(savedTask.getStatus()).isEqualTo(TaskStatus.waiting);
        assertThat(savedTask.getFileName()).isEqualTo("mod.esm");
        assertThat(savedTask.getPromptId()).isNull();
    }

    /** 默认 Prompt 模式应将 DEFAULT_PROMPT 传递给引擎 */
    @Test
    void upload_defaultPrompt_passesDefaultPromptToEngine() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var captor = ArgumentCaptor.forClass(EngineClient.EngineTranslateRequest.class);

        fileUploadService.upload(file, null, null, null, null);

        verify(engineClient).submitTranslation(captor.capture());
        assertThat(captor.getValue().customPrompt()).isEqualTo(PromptService.DEFAULT_PROMPT);
    }

    /** 选择已有 Prompt 模式应设置 promptId 并传递对应内容给引擎 */
    @Test
    void upload_withExistingPromptId_setsPromptIdAndPassesContent() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        when(promptService.getPromptContent(42L)).thenReturn("翻译为中文");
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var taskCaptor = ArgumentCaptor.forClass(TranslationTask.class);
        var engineCaptor = ArgumentCaptor.forClass(EngineClient.EngineTranslateRequest.class);

        fileUploadService.upload(file, null, 42L, null, null);

        verify(translationTaskRepository).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getPromptId()).isEqualTo(42L);

        verify(engineClient).submitTranslation(engineCaptor.capture());
        assertThat(engineCaptor.getValue().customPrompt()).isEqualTo("翻译为中文");
    }

    /** 现场编写 Prompt 模式应创建新模板并设置 promptId */
    @Test
    void upload_withNewPrompt_createsPromptAndSetsId() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        var now = LocalDateTime.now();
        var createdPrompt = new PromptListResponse(99L, "测试模板", "自定义翻译指令", 0, now, now);
        when(promptService.createPrompt(any(PromptRequest.class))).thenReturn(createdPrompt);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var taskCaptor = ArgumentCaptor.forClass(TranslationTask.class);
        var engineCaptor = ArgumentCaptor.forClass(EngineClient.EngineTranslateRequest.class);

        fileUploadService.upload(file, null, null, "测试模板", "自定义翻译指令");

        verify(promptService).createPrompt(any(PromptRequest.class));
        verify(translationTaskRepository).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getPromptId()).isEqualTo(99L);

        verify(engineClient).submitTranslation(engineCaptor.capture());
        assertThat(engineCaptor.getValue().customPrompt()).isEqualTo("自定义翻译指令");
    }

    /** 现场编写优先于选择已有 Prompt */
    @Test
    void upload_withBothNewContentAndPromptId_prefersNewContent() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        var now = LocalDateTime.now();
        var createdPrompt = new PromptListResponse(100L, "新模板", "新内容", 0, now, now);
        when(promptService.createPrompt(any(PromptRequest.class))).thenReturn(createdPrompt);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var taskCaptor = ArgumentCaptor.forClass(TranslationTask.class);

        fileUploadService.upload(file, null, 42L, "新模板", "新内容");

        verify(promptService).createPrompt(any(PromptRequest.class));
        verify(promptService, never()).getPromptContent(any());
        verify(translationTaskRepository).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getPromptId()).isEqualTo(100L);
    }

    /** 无效 Prompt ID 应抛出异常 */
    @Test
    void upload_withInvalidPromptId_throwsException() {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        when(promptService.getPromptContent(999L)).thenThrow(new PromptService.PromptNotFoundException(999L));

        assertThatThrownBy(() -> fileUploadService.upload(file, null, 999L, null, null))
                .isInstanceOf(PromptService.PromptNotFoundException.class);
    }

    /** 上传应将 dictionaryEntries 传递给引擎 */
    @Test
    void upload_withDictionary_passesToEngine() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);

        var entry = new DictionaryEntry();
        entry.setSourceText("Dragon");
        entry.setTargetText("龙");
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of(entry));

        var captor = ArgumentCaptor.forClass(EngineClient.EngineTranslateRequest.class);

        fileUploadService.upload(file, null, null, null, null);

        verify(engineClient).submitTranslation(captor.capture());
        var request = captor.getValue();
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
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());
        doThrow(new RuntimeException("engine down")).when(engineClient).submitTranslation(any());

        var response = fileUploadService.upload(file, null, null, null, null);

        assertThat(response.taskId()).isNotBlank();
        verify(translationTaskRepository).insert(any(TranslationTask.class));
    }

    /** 每次上传应生成不同的 taskId */
    @Test
    void upload_multipleCalls_generateUniqueTaskIds() throws IOException {
        var content = createEsmContent();
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var file1 = new MockMultipartFile("file", "a.esm", "application/octet-stream", content);
        var file2 = new MockMultipartFile("file", "b.esm", "application/octet-stream", content);

        var response1 = fileUploadService.upload(file1, null, null, null, null);
        var response2 = fileUploadService.upload(file2, null, null, null, null);

        assertThat(response1.taskId()).isNotEqualTo(response2.taskId());
    }

    /** 空白 newPromptContent 应回退到默认 Prompt */
    @Test
    void upload_blankNewPromptContent_fallsBackToDefault() throws IOException {
        var content = createEsmContent();
        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", content);
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var taskCaptor = ArgumentCaptor.forClass(TranslationTask.class);

        fileUploadService.upload(file, null, null, "名称", "   ");

        verify(promptService, never()).createPrompt(any());
        verify(translationTaskRepository).insert(taskCaptor.capture());
        assertThat(taskCaptor.getValue().getPromptId()).isNull();
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
