package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 翻译确认记录实体
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("translation_confirmation")
public class TranslationConfirmation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("task_id")
    private String taskId;

    @TableField("record_id")
    private String recordId;

    @TableField("record_type")
    private String recordType;

    @TableField("source_text")
    private String sourceText;

    @TableField("target_text")
    private String targetText;

    @TableField("status")
    private String status = "pending";

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
