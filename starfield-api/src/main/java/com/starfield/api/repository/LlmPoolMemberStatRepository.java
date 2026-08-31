package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.LlmPoolMemberStat;
import org.apache.ibatis.annotations.Mapper;

/**
 * 池成员按天用量统计 Mapper
 */
@Mapper
public interface LlmPoolMemberStatRepository extends BaseMapper<LlmPoolMemberStat> {
}
