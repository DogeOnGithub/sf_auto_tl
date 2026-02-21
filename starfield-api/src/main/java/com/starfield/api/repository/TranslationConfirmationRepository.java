package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.TranslationConfirmation;
import org.apache.ibatis.annotations.Mapper;

/**
 * 翻译确认记录 Mapper
 */
@Mapper
public interface TranslationConfirmationRepository extends BaseMapper<TranslationConfirmation> {
}
