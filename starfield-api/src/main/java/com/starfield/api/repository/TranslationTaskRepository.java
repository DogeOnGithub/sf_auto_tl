package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.TranslationTask;
import org.apache.ibatis.annotations.Mapper;

/**
 * 翻译任务 Mapper
 */
@Mapper
public interface TranslationTaskRepository extends BaseMapper<TranslationTask> {
}
