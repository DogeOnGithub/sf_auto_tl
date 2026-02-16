package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.TranslationCache;
import org.apache.ibatis.annotations.Mapper;

/**
 * 翻译缓存 Mapper
 */
@Mapper
public interface TranslationCacheRepository extends BaseMapper<TranslationCache> {
}
