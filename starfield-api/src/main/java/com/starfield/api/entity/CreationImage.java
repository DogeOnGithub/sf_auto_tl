package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Mod 作品图片实体
 */
/**
 * Mod 作品图片实体
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("creation_image")
public class CreationImage {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("creation_id")
    private Long creationId;

    @TableField("image_path")
    private String imagePath;

    @TableField("sort_order")
    private Integer sortOrder = 0;

    @TableLogic
    @TableField("deleted")
    private Boolean deleted = false;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
