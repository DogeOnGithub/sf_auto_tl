package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.starfield.api.dto.DictionaryEntryRequest;
import com.starfield.api.dto.DictionaryEntryResponse;
import com.starfield.api.dto.DictionaryPageResponse;
import com.starfield.api.entity.DictionaryEntry;
import com.starfield.api.repository.DictionaryEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 词典管理服务，处理词条的增删改查
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DictionaryService {

    final DictionaryEntryRepository dictionaryEntryRepository;

    /**
     * 分页查询词条，支持按原文或译文模糊搜索
     * <p>
     * 按更新时间倒序，新增或刚编辑的词条排在第一页。更新时间相同时再按 ID 倒序兜底，
     * 否则数据库不保证返回顺序，翻页时同一词条可能在两页重复出现或被漏掉
     * <p>
     * 翻译流程不走这里，FileUploadService 直接查全量词条传给引擎
     *
     * @param keyword 搜索关键词，为空时查询全部
     * @param page    页码，从 1 开始
     * @param size    每页条数
     * @return 分页响应
     */
    public DictionaryPageResponse getEntries(String keyword, int page, int size) {
        log.info("[getEntries] 分页查询词条 keyword {} page {} size {}", keyword, page, size);
        var wrapper = new LambdaQueryWrapper<DictionaryEntry>();
        if (Objects.nonNull(keyword) && !keyword.isBlank()) {
            // 用 and 包一层括号 避免 OR 条件和后续追加的条件混在一起
            wrapper.and(w -> w.like(DictionaryEntry::getSourceText, keyword)
                    .or()
                    .like(DictionaryEntry::getTargetText, keyword));
        }
        wrapper.orderByDesc(DictionaryEntry::getUpdatedAt)
                .orderByDesc(DictionaryEntry::getId);

        var pageResult = dictionaryEntryRepository.selectPage(new Page<>(page, size), wrapper);
        var records = pageResult.getRecords().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        log.info("[getEntries] 返回词条 count {} total {}", records.size(), pageResult.getTotal());
        return new DictionaryPageResponse(records, pageResult.getTotal(), pageResult.getCurrent(), pageResult.getPages());
    }

    /**
     * 创建词条，若原文已存在则更新译文（幂等 upsert）
     *
     * @param request 词条请求
     * @return 词条响应
     */
    public DictionaryEntryResponse createEntry(DictionaryEntryRequest request) {
        log.info("[createEntry] 创建词条 sourceText {}", request.sourceText());
        validateEntry(request);

        var existing = findBySourceText(request.sourceText());
        if (Objects.nonNull(existing)) {
            existing.setTargetText(request.targetText());
            dictionaryEntryRepository.updateById(existing);
            log.info("[createEntry] 更新已有词条 id {}", existing.getId());
            return toResponse(existing);
        }

        var entry = new DictionaryEntry();
        entry.setSourceText(request.sourceText());
        entry.setTargetText(request.targetText());
        dictionaryEntryRepository.insert(entry);
        log.info("[createEntry] 新建词条 id {}", entry.getId());
        return toResponse(entry);
    }

    /**
     * 更新指定 ID 的词条
     *
     * @param id      词条 ID
     * @param request 词条请求
     * @return 词条响应
     */
    public DictionaryEntryResponse updateEntry(Long id, DictionaryEntryRequest request) {
        log.info("[updateEntry] 更新词条 id {}", id);
        validateEntry(request);

        var entry = dictionaryEntryRepository.selectById(id);
        if (Objects.isNull(entry)) {
            throw new EntryNotFoundException(id);
        }
        entry.setSourceText(request.sourceText());
        entry.setTargetText(request.targetText());
        dictionaryEntryRepository.updateById(entry);
        log.info("[updateEntry] 词条更新成功 id {}", id);
        return toResponse(entry);
    }

    /**
     * 删除指定 ID 的词条
     *
     * @param id 词条 ID
     */
    public void deleteEntry(Long id) {
        log.info("[deleteEntry] 删除词条 id {}", id);
        if (Objects.isNull(dictionaryEntryRepository.selectById(id))) {
            throw new EntryNotFoundException(id);
        }
        dictionaryEntryRepository.deleteById(id);
        log.info("[deleteEntry] 词条删除成功 id {}", id);
    }

    /**
     * 根据原文查找词条
     */
    private DictionaryEntry findBySourceText(String sourceText) {
        var wrapper = new LambdaQueryWrapper<DictionaryEntry>()
                .eq(DictionaryEntry::getSourceText, sourceText);
        return dictionaryEntryRepository.selectOne(wrapper);
    }

    /**
     * 校验词条原文和译文非空
     */
    private void validateEntry(DictionaryEntryRequest request) {
        if (Objects.isNull(request.sourceText()) || request.sourceText().isBlank()
                || Objects.isNull(request.targetText()) || request.targetText().isBlank()) {
            log.warn("[validateEntry] 词条原文或译文为空");
            throw new EmptyEntryException();
        }
    }

    /**
     * 实体转响应 DTO
     */
    private DictionaryEntryResponse toResponse(DictionaryEntry entry) {
        return new DictionaryEntryResponse(entry.getId(), entry.getSourceText(), entry.getTargetText());
    }

    /**
     * 词条内容为空异常
     */
    public static class EmptyEntryException extends RuntimeException {
        public EmptyEntryException() {
            super("词条原文和译文不能为空");
        }
    }

    /**
     * 词条不存在异常
     */
    public static class EntryNotFoundException extends RuntimeException {
        public EntryNotFoundException(Long id) {
            super("词条不存在 id " + id);
        }
    }
}
