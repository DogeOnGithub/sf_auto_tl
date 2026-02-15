package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.Creation;
import org.apache.ibatis.annotations.Mapper;

/**
 * Mod 作品 Mapper
 */
@Mapper
public interface CreationRepository extends BaseMapper<Creation> {
}
