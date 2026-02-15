package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starfield.api.dto.DictionaryEntryRequest;
import com.starfield.api.entity.DictionaryEntry;
import com.starfield.api.repository.DictionaryEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DictionaryServiceTest {

    @Mock
    DictionaryEntryRepository dictionaryEntryRepository;

    @InjectMocks
    DictionaryService dictionaryService;

    /** 无关键词时应返回全部词条 */
    @Test
    void getEntries_noKeyword_returnsAll() {
        var entry = createEntry(1L, "Dragon", "龙");
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of(entry));

        var response = dictionaryService.getEntries(null);

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().get(0).sourceText()).isEqualTo("Dragon");
        verify(dictionaryEntryRepository).selectList(isNull());
    }

    /** 空白关键词应返回全部词条 */
    @Test
    void getEntries_blankKeyword_returnsAll() {
        when(dictionaryEntryRepository.selectList(isNull())).thenReturn(List.of());

        var response = dictionaryService.getEntries("   ");

        assertThat(response.entries()).isEmpty();
        verify(dictionaryEntryRepository).selectList(isNull());
    }

    /** 有关键词时应调用搜索方法 */
    @Test
    void getEntries_withKeyword_searchesByKeyword() {
        var entry = createEntry(1L, "Dragon", "龙");
        when(dictionaryEntryRepository.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(entry));

        var response = dictionaryService.getEntries("Dragon");

        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().get(0).sourceText()).isEqualTo("Dragon");
        verify(dictionaryEntryRepository).selectList(any(LambdaQueryWrapper.class));
    }

    /** 创建新词条应保存并返回响应 */
    @Test
    void createEntry_newSourceText_createsEntry() {
        when(dictionaryEntryRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        var response = dictionaryService.createEntry(new DictionaryEntryRequest("Sword", "剑"));

        var captor = ArgumentCaptor.forClass(DictionaryEntry.class);
        verify(dictionaryEntryRepository).insert(captor.capture());
        assertThat(captor.getValue().getSourceText()).isEqualTo("Sword");
        assertThat(captor.getValue().getTargetText()).isEqualTo("剑");
        assertThat(response.sourceText()).isEqualTo("Sword");
        assertThat(response.targetText()).isEqualTo("剑");
    }

    /** 重复原文应更新已有词条的译文 */
    @Test
    void createEntry_duplicateSourceText_updatesExisting() {
        var existing = createEntry(1L, "Sword", "旧译文");
        when(dictionaryEntryRepository.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        var response = dictionaryService.createEntry(new DictionaryEntryRequest("Sword", "剑"));

        verify(dictionaryEntryRepository).updateById(existing);
        assertThat(existing.getTargetText()).isEqualTo("剑");
        assertThat(response.id()).isEqualTo(1L);
        assertThat(response.targetText()).isEqualTo("剑");
    }

    /** 原文为空应抛出 EmptyEntryException */
    @Test
    void createEntry_emptySourceText_throwsEmptyEntryException() {
        assertThatThrownBy(() -> dictionaryService.createEntry(new DictionaryEntryRequest("", "译文")))
                .isInstanceOf(DictionaryService.EmptyEntryException.class);
        verify(dictionaryEntryRepository, never()).insert(any(DictionaryEntry.class));
    }

    /** 译文为空应抛出 EmptyEntryException */
    @Test
    void createEntry_emptyTargetText_throwsEmptyEntryException() {
        assertThatThrownBy(() -> dictionaryService.createEntry(new DictionaryEntryRequest("原文", "")))
                .isInstanceOf(DictionaryService.EmptyEntryException.class);
        verify(dictionaryEntryRepository, never()).insert(any(DictionaryEntry.class));
    }

    /** 原文为 null 应抛出 EmptyEntryException */
    @Test
    void createEntry_nullSourceText_throwsEmptyEntryException() {
        assertThatThrownBy(() -> dictionaryService.createEntry(new DictionaryEntryRequest(null, "译文")))
                .isInstanceOf(DictionaryService.EmptyEntryException.class);
    }

    /** 纯空白原文应抛出 EmptyEntryException */
    @Test
    void createEntry_whitespaceSourceText_throwsEmptyEntryException() {
        assertThatThrownBy(() -> dictionaryService.createEntry(new DictionaryEntryRequest("   ", "译文")))
                .isInstanceOf(DictionaryService.EmptyEntryException.class);
    }

    /** 更新存在的词条应成功 */
    @Test
    void updateEntry_existingId_updatesEntry() {
        var entry = createEntry(1L, "Sword", "剑");
        when(dictionaryEntryRepository.selectById(1L)).thenReturn(entry);

        var response = dictionaryService.updateEntry(1L, new DictionaryEntryRequest("Shield", "盾"));

        verify(dictionaryEntryRepository).updateById(entry);
        assertThat(entry.getSourceText()).isEqualTo("Shield");
        assertThat(entry.getTargetText()).isEqualTo("盾");
        assertThat(response.id()).isEqualTo(1L);
    }

    /** 更新不存在的词条应抛出 EntryNotFoundException */
    @Test
    void updateEntry_nonExistingId_throwsEntryNotFoundException() {
        when(dictionaryEntryRepository.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> dictionaryService.updateEntry(99L, new DictionaryEntryRequest("a", "b")))
                .isInstanceOf(DictionaryService.EntryNotFoundException.class);
    }

    /** 更新时原文为空应抛出 EmptyEntryException */
    @Test
    void updateEntry_emptyContent_throwsEmptyEntryException() {
        assertThatThrownBy(() -> dictionaryService.updateEntry(1L, new DictionaryEntryRequest("", "b")))
                .isInstanceOf(DictionaryService.EmptyEntryException.class);
    }

    /** 删除存在的词条应成功 */
    @Test
    void deleteEntry_existingId_deletesEntry() {
        var entry = createEntry(1L, "Sword", "剑");
        when(dictionaryEntryRepository.selectById(1L)).thenReturn(entry);

        dictionaryService.deleteEntry(1L);

        verify(dictionaryEntryRepository).deleteById(1L);
    }

    /** 删除不存在的词条应抛出 EntryNotFoundException */
    @Test
    void deleteEntry_nonExistingId_throwsEntryNotFoundException() {
        when(dictionaryEntryRepository.selectById(99L)).thenReturn(null);

        assertThatThrownBy(() -> dictionaryService.deleteEntry(99L))
                .isInstanceOf(DictionaryService.EntryNotFoundException.class);
    }

    private DictionaryEntry createEntry(Long id, String sourceText, String targetText) {
        var entry = new DictionaryEntry();
        entry.setId(id);
        entry.setSourceText(sourceText);
        entry.setTargetText(targetText);
        return entry;
    }
}
