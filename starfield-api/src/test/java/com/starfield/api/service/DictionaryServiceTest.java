package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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

    /** 无关键词时应按页码分页查询 且不带搜索条件 */
    @Test
    @SuppressWarnings("unchecked")
    void getEntries_noKeyword_returnsPage() {
        var entry = createEntry(1L, "Dragon", "龙");
        stubSelectPage(List.of(entry), 21);

        var response = dictionaryService.getEntries(null, 2, 20);

        assertThat(response.records()).hasSize(1);
        assertThat(response.records().get(0).sourceText()).isEqualTo("Dragon");
        assertThat(response.total()).isEqualTo(21);
        assertThat(response.current()).isEqualTo(2);
        var pageCaptor = ArgumentCaptor.forClass(Page.class);
        var wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dictionaryEntryRepository).selectPage(pageCaptor.capture(), wrapperCaptor.capture());
        assertThat(pageCaptor.getValue().getCurrent()).isEqualTo(2);
        assertThat(pageCaptor.getValue().getSize()).isEqualTo(20);
        assertThat(wrapperCaptor.getValue().isEmptyOfWhere()).isTrue();
    }

    /** 空白关键词视为无关键词 不带搜索条件 */
    @Test
    @SuppressWarnings("unchecked")
    void getEntries_blankKeyword_returnsAll() {
        stubSelectPage(List.of(), 0);

        var response = dictionaryService.getEntries("   ", 1, 20);

        assertThat(response.records()).isEmpty();
        var wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dictionaryEntryRepository).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().isEmptyOfWhere()).isTrue();
    }

    /** 有关键词时应带上搜索条件 */
    @Test
    @SuppressWarnings("unchecked")
    void getEntries_withKeyword_searchesByKeyword() {
        var entry = createEntry(1L, "Dragon", "龙");
        stubSelectPage(List.of(entry), 1);

        var response = dictionaryService.getEntries("Dragon", 1, 20);

        assertThat(response.records()).hasSize(1);
        assertThat(response.records().get(0).sourceText()).isEqualTo("Dragon");
        var wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(dictionaryEntryRepository).selectPage(any(Page.class), wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().isEmptyOfWhere()).isFalse();
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

    /** 模拟分页查询 把传入的 Page 填上记录和总数后原样返回 与 MyBatis-Plus 分页插件行为一致 */
    @SuppressWarnings("unchecked")
    private void stubSelectPage(List<DictionaryEntry> records, long total) {
        when(dictionaryEntryRepository.selectPage(any(Page.class), any(LambdaQueryWrapper.class)))
                .thenAnswer(invocation -> {
                    Page<DictionaryEntry> page = invocation.getArgument(0);
                    page.setRecords(records);
                    page.setTotal(total);
                    return page;
                });
    }

    private DictionaryEntry createEntry(Long id, String sourceText, String targetText) {
        var entry = new DictionaryEntry();
        entry.setId(id);
        entry.setSourceText(sourceText);
        entry.setTargetText(targetText);
        return entry;
    }
}
