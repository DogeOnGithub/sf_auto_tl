package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.DictionaryEntry;
import org.apache.ibatis.annotations.Mapper;

/**
 * 词典词条 Mapper
 */
@Mapper
public interface DictionaryEntryRepository extends BaseMapper<DictionaryEntry> {
}
