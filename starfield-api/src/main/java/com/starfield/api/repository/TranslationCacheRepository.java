package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.TranslationCache;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 翻译缓存 Mapper
 */
@Mapper
public interface TranslationCacheRepository extends BaseMapper<TranslationCache> {

    /**
     * 批量查询缓存命中记录
     *
     * @param targetLang 目标语言
     * @param items      查询条件列表（需包含 recordType、subrecordType、sourceText）
     * @return 命中的缓存记录列表
     */
    List<TranslationCache> batchQueryCache(@Param("targetLang") String targetLang,
                                           @Param("items") List<TranslationCache> items);

    /**
     * 批量 UPSERT 缓存记录（存在则更新 target_text，不存在则插入）
     *
     * @param items 缓存记录列表
     */
    void batchUpsertCache(@Param("items") List<TranslationCache> items);
}
