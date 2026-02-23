package com.starfield.api.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.starfield.api.entity.TranslationConfirmation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 翻译确认记录 Mapper
 */
@Mapper
public interface TranslationConfirmationRepository extends BaseMapper<TranslationConfirmation> {

    /**
     * 批量插入确认记录 重复的 task_id + record_id 自动忽略
     *
     * @param items 确认记录列表
     */
    void batchInsertIgnore(@Param("items") List<TranslationConfirmation> items);

    /**
     * 批量更新确认记录状态
     *
     * @param taskId 任务 ID
     * @param ids    记录 ID 列表
     * @param status 目标状态
     */
    void batchUpdateStatus(@Param("taskId") String taskId,
                           @Param("ids") List<Long> ids,
                           @Param("status") String status);
}
