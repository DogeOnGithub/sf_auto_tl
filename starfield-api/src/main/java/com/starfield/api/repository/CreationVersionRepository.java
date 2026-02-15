package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.CreationVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mod 作品版本 Mapper
 */
@Mapper
public interface CreationVersionRepository extends BaseMapper<CreationVersion> {
}
