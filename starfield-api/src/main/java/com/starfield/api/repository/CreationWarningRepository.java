package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.CreationWarning;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mod 作品警告记录 Mapper
 */
@Mapper
public interface CreationWarningRepository extends BaseMapper<CreationWarning> {
}
