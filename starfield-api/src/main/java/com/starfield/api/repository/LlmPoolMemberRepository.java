package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.LlmPoolMember;
import org.apache.ibatis.annotations.Mapper;

/**
 * 默认 LLM 凭证池成员 Mapper
 */
@Mapper
public interface LlmPoolMemberRepository extends BaseMapper<LlmPoolMember> {
}
