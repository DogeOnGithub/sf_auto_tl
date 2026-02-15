package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.CustomPrompt;
import org.apache.ibatis.annotations.Mapper;

/**
 * 自定义 Prompt Mapper
 */
@Mapper
public interface CustomPromptRepository extends BaseMapper<CustomPrompt> {
}
