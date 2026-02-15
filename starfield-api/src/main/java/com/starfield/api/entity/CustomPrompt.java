package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 自定义 Prompt 实体
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("custom_prompt")
public class CustomPrompt {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("content")
    private String content;

    @TableField("is_active")
    private Boolean isActive = true;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
