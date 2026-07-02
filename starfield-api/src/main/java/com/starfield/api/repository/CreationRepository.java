package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.starfield.api.entity.Creation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Mod 作品 Mapper
 */
@Mapper
public interface CreationRepository extends BaseMapper<Creation> {

    /**
     * 按最新版本添加时间倒序分页查询作品（无版本的作品排在最后）
     *
     * @param page    分页参数
     * @param keyword 搜索关键词（可选，匹配名称/译名/作者/标签）
     * @return 分页结果
     */
    IPage<Creation> selectPageOrderByLatestVersion(Page<Creation> page, @Param("keyword") String keyword);
}
