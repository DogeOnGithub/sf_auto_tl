package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starfield.api.dto.PromptRequest;
import com.starfield.api.entity.CustomPrompt;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.repository.CustomPromptRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock
    CustomPromptRepository customPromptRepository;

    @Mock
    TranslationTaskRepository translationTaskRepository;

    @InjectMocks
    PromptService promptService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(promptService, "maxLength", 10000);
    }

    /** 创建 Prompt 正常流程应返回包含名称和内容的响应 */
    @Test
    void createPrompt_validRequest_returnsListResponse() {
        var request = new PromptRequest("测试模板", "翻译指令内容");

        var response = promptService.createPrompt(request);

        assertThat(response.name()).isEqualTo("测试模板");
        assertThat(response.content()).isEqualTo("翻译指令内容");
        assertThat(response.usageCount()).isZero();
        verify(customPromptRepository).insert(any(CustomPrompt.class));
    }

    /** 创建 Prompt 时名称为空应抛出校验异常 */
    @Test
    void createPrompt_emptyName_throwsValidationException() {
        var request = new PromptRequest("", "内容");

        assertThatThrownBy(() -> promptService.createPrompt(request))
                .isInstanceOf(PromptService.PromptValidationException.class)
                .hasMessage("Prompt 名称不能为空");
        verify(customPromptRepository, never()).insert(any(CustomPrompt.class));
    }

    /** 创建 Prompt 时名称为 null 应抛出校验异常 */
    @Test
    void createPrompt_nullName_throwsValidationException() {
        var request = new PromptRequest(null, "内容");

        assertThatThrownBy(() -> promptService.createPrompt(request))
                .isInstanceOf(PromptService.PromptValidationException.class)
                .hasMessage("Prompt 名称不能为空");
    }

    /** 创建 Prompt 时内容为空应抛出校验异常 */
    @Test
    void createPrompt_emptyContent_throwsValidationException() {
        var request = new PromptRequest("名称", "");

        assertThatThrownBy(() -> promptService.createPrompt(request))
                .isInstanceOf(PromptService.PromptValidationException.class)
                .hasMessage("Prompt 内容不能为空");
        verify(customPromptRepository, never()).insert(any(CustomPrompt.class));
    }

    /** 创建 Prompt 时内容为 null 应抛出校验异常 */
    @Test
    void createPrompt_nullContent_throwsValidationException() {
        var request = new PromptRequest("名称", null);

        assertThatThrownBy(() -> promptService.createPrompt(request))
                .isInstanceOf(PromptService.PromptValidationException.class)
                .hasMessage("Prompt 内容不能为空");
    }

    /** 创建 Prompt 时内容超过最大长度应抛出校验异常 */
    @Test
    void createPrompt_contentExceedsMaxLength_throwsValidationException() {
        ReflectionTestUtils.setField(promptService, "maxLength", 10);
        var request = new PromptRequest("名称", "a".repeat(11));

        assertThatThrownBy(() -> promptService.createPrompt(request))
                .isInstanceOf(PromptService.PromptValidationException.class)
                .hasMessage("Prompt 内容超过最大长度限制 10");
        verify(customPromptRepository, never()).insert(any(CustomPrompt.class));
    }

    /** 查询列表应返回按更新时间倒序排列的结果并附带使用次数 */
    @Test
    void listPrompts_returnsOrderedListWithUsageCounts() {
        var prompt1 = buildPrompt(1L, "模板A", "内容A", LocalDateTime.of(2024, 1, 1, 0, 0));
        var prompt2 = buildPrompt(2L, "模板B", "内容B", LocalDateTime.of(2024, 1, 2, 0, 0));
        when(customPromptRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(prompt2, prompt1));
        when(translationTaskRepository.selectCount(any(LambdaQueryWrapper.class)))
                .thenReturn(3L, 1L);

        var result = promptService.listPrompts();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("模板B");
        assertThat(result.get(0).usageCount()).isEqualTo(3);
        assertThat(result.get(1).name()).isEqualTo("模板A");
        assertThat(result.get(1).usageCount()).isEqualTo(1);
    }

    /** 查询详情应返回 Prompt 信息和关联任务列表 */
    @Test
    void getPromptDetail_existingId_returnsDetailWithTasks() {
        var prompt = buildPrompt(1L, "模板", "内容", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(customPromptRepository.selectById(1L)).thenReturn(prompt);
        when(translationTaskRepository.selectCount(any(LambdaQueryWrapper.class))).thenReturn(2L);

        var task = new TranslationTask();
        task.setTaskId("task-001");
        task.setFileName("test.esm");
        task.setStatus(TaskStatus.completed);
        task.setCreatedAt(LocalDateTime.of(2024, 1, 1, 12, 0));
        when(translationTaskRepository.selectList(any(LambdaQueryWrapper.class)))
                .thenReturn(List.of(task));

        var result = promptService.getPromptDetail(1L);

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.name()).isEqualTo("模板");
        assertThat(result.usageCount()).isEqualTo(2);
        assertThat(result.tasks()).hasSize(1);
        assertThat(result.tasks().get(0).taskId()).isEqualTo("task-001");
    }

    /** 查询不存在的 Prompt 详情应抛出异常 */
    @Test
    void getPromptDetail_nonExistentId_throwsNotFoundException() {
        when(customPromptRepository.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> promptService.getPromptDetail(999L))
                .isInstanceOf(PromptService.PromptNotFoundException.class);
    }

    /** 更新 Prompt 正常流程应返回更新后的响应 */
    @Test
    void updatePrompt_validRequest_returnsUpdatedResponse() {
        var prompt = buildPrompt(1L, "旧名称", "旧内容", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(customPromptRepository.selectById(1L)).thenReturn(prompt);
        when(translationTaskRepository.selectCount(any(LambdaQueryWrapper.class))).thenReturn(5L);

        var request = new PromptRequest("新名称", "新内容");
        var result = promptService.updatePrompt(1L, request);

        assertThat(result.name()).isEqualTo("新名称");
        assertThat(result.content()).isEqualTo("新内容");
        assertThat(result.usageCount()).isEqualTo(5);
        verify(customPromptRepository).updateById(prompt);
    }

    /** 更新不存在的 Prompt 应抛出异常 */
    @Test
    void updatePrompt_nonExistentId_throwsNotFoundException() {
        when(customPromptRepository.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> promptService.updatePrompt(999L, new PromptRequest("名称", "内容")))
                .isInstanceOf(PromptService.PromptNotFoundException.class);
    }

    /** 删除 Prompt 正常流程应调用 deleteById */
    @Test
    void deletePrompt_existingId_deletesSuccessfully() {
        var prompt = buildPrompt(1L, "模板", "内容", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(customPromptRepository.selectById(1L)).thenReturn(prompt);

        promptService.deletePrompt(1L);

        verify(customPromptRepository).deleteById(1L);
    }

    /** 删除不存在的 Prompt 应抛出异常 */
    @Test
    void deletePrompt_nonExistentId_throwsNotFoundException() {
        when(customPromptRepository.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> promptService.deletePrompt(999L))
                .isInstanceOf(PromptService.PromptNotFoundException.class);
    }

    /** 获取 Prompt 内容正常流程应返回内容字符串 */
    @Test
    void getPromptContent_existingId_returnsContent() {
        var prompt = buildPrompt(1L, "模板", "翻译指令", LocalDateTime.of(2024, 1, 1, 0, 0));
        when(customPromptRepository.selectById(1L)).thenReturn(prompt);

        var content = promptService.getPromptContent(1L);

        assertThat(content).isEqualTo("翻译指令");
    }

    /** 获取不存在的 Prompt 内容应抛出异常 */
    @Test
    void getPromptContent_nonExistentId_throwsNotFoundException() {
        when(customPromptRepository.selectById(999L)).thenReturn(null);

        assertThatThrownBy(() -> promptService.getPromptContent(999L))
                .isInstanceOf(PromptService.PromptNotFoundException.class);
    }

    /**
     * 构建测试用 CustomPrompt 实体
     */
    private CustomPrompt buildPrompt(Long id, String name, String content, LocalDateTime updatedAt) {
        var prompt = new CustomPrompt();
        prompt.setId(id);
        prompt.setName(name);
        prompt.setContent(content);
        prompt.setCreatedAt(updatedAt);
        prompt.setUpdatedAt(updatedAt);
        return prompt;
    }
}
