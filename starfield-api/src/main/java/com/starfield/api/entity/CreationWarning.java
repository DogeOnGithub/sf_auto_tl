package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Mod 作品警告记录实体
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("creation_warning")
public class CreationWarning {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("creation_id")
    private Long creationId;

    @TableField("content")
    private String content;

    @TableField("status")
    private String status;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
