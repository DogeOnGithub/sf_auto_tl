package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.CreationImage;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mod 作品图片 Mapper
 */
@Mapper
public interface CreationImageRepository extends BaseMapper<CreationImage> {
}
