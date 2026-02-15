package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starfield.api.dto.PromptRequest;
import com.starfield.api.entity.CustomPrompt;
import com.starfield.api.repository.CustomPromptRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock
    CustomPromptRepository customPromptRepository;

    @InjectMocks
    PromptService promptService;

    /** 无自定义 Prompt 时应返回默认 Prompt */
    @Test
    void getCurrentPrompt_noCustom_returnsDefault() {
        ReflectionTestUtils.setField(promptService, "maxLength", 10000);
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        var response = promptService.getCurrentPrompt();

        assertThat(response.content()).isEqualTo(PromptService.DEFAULT_PROMPT);
        assertThat(response.isCustom()).isFalse();
    }

    /** 有自定义 Prompt 时应返回自定义内容 */
    @Test
    void getCurrentPrompt_hasCustom_returnsCustom() {
        var prompt = new CustomPrompt();
        prompt.setId(1L);
        prompt.setContent("自定义翻译指令");
        prompt.setIsActive(true);
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(prompt);

        var response = promptService.getCurrentPrompt();

        assertThat(response.content()).isEqualTo("自定义翻译指令");
        assertThat(response.isCustom()).isTrue();
    }

    /** 保存 Prompt 时无已有记录应创建新记录 */
    @Test
    void savePrompt_noExisting_createsNew() {
        ReflectionTestUtils.setField(promptService, "maxLength", 10000);
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        var response = promptService.savePrompt(new PromptRequest("新 Prompt"));

        assertThat(response.content()).isEqualTo("新 Prompt");
        assertThat(response.isCustom()).isTrue();

        var captor = ArgumentCaptor.forClass(CustomPrompt.class);
        verify(customPromptRepository).insert(captor.capture());
        assertThat(captor.getValue().getContent()).isEqualTo("新 Prompt");
        assertThat(captor.getValue().getIsActive()).isTrue();
    }

    /** 保存 Prompt 时有已有记录应更新内容 */
    @Test
    void savePrompt_hasExisting_updatesContent() {
        ReflectionTestUtils.setField(promptService, "maxLength", 10000);
        var existing = new CustomPrompt();
        existing.setId(1L);
        existing.setContent("旧内容");
        existing.setIsActive(true);
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        var response = promptService.savePrompt(new PromptRequest("更新后的内容"));

        assertThat(response.content()).isEqualTo("更新后的内容");
        assertThat(response.isCustom()).isTrue();
        verify(customPromptRepository).updateById(existing);
        assertThat(existing.getContent()).isEqualTo("更新后的内容");
    }

    /** Prompt 内容超过最大长度应抛出异常 */
    @Test
    void savePrompt_tooLong_throwsPromptTooLongException() {
        ReflectionTestUtils.setField(promptService, "maxLength", 10);

        var longContent = "a".repeat(11);

        assertThatThrownBy(() -> promptService.savePrompt(new PromptRequest(longContent)))
                .isInstanceOf(PromptService.PromptTooLongException.class);
        verify(customPromptRepository, never()).insert(any(CustomPrompt.class));
    }

    /** Prompt 内容为 null 应抛出异常 */
    @Test
    void savePrompt_nullContent_throwsPromptTooLongException() {
        ReflectionTestUtils.setField(promptService, "maxLength", 10000);

        assertThatThrownBy(() -> promptService.savePrompt(new PromptRequest(null)))
                .isInstanceOf(PromptService.PromptTooLongException.class);
        verify(customPromptRepository, never()).insert(any(CustomPrompt.class));
    }

    /** 恢复默认时有自定义 Prompt 应删除并返回默认 */
    @Test
    void resetPrompt_hasCustom_deletesAndReturnsDefault() {
        var existing = new CustomPrompt();
        existing.setId(1L);
        existing.setContent("自定义内容");
        existing.setIsActive(true);
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        var response = promptService.resetPrompt();

        assertThat(response.content()).isEqualTo(PromptService.DEFAULT_PROMPT);
        assertThat(response.isCustom()).isFalse();
        verify(customPromptRepository).deleteById(1L);
    }

    /** 恢复默认时无自定义 Prompt 应直接返回默认 */
    @Test
    void resetPrompt_noCustom_returnsDefault() {
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        var response = promptService.resetPrompt();

        assertThat(response.content()).isEqualTo(PromptService.DEFAULT_PROMPT);
        assertThat(response.isCustom()).isFalse();
        verify(customPromptRepository, never()).deleteById(any(CustomPrompt.class));
    }

    /** 保存刚好等于最大长度的 Prompt 应成功 */
    @Test
    void savePrompt_exactMaxLength_succeeds() {
        ReflectionTestUtils.setField(promptService, "maxLength", 10);
        when(customPromptRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        var content = "a".repeat(10);
        var response = promptService.savePrompt(new PromptRequest(content));

        assertThat(response.content()).isEqualTo(content);
        assertThat(response.isCustom()).isTrue();
        verify(customPromptRepository).insert(any(CustomPrompt.class));
    }
}
