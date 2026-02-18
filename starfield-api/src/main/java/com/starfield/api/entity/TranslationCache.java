package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 翻译缓存实体
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("translation_cache")
public class TranslationCache {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private String taskId;

    @TableField("record_type")
    private String recordType;

    @TableField("subrecord_type")
    private String subrecordType;

    @TableField("source_text")
    private String sourceText;

    @TableField("target_text")
    private String targetText;

    @TableField("target_lang")
    private String targetLang;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
