package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Mod 作品实体（基本信息）
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("creation")
public class Creation {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("name")
    private String name;

    @TableField("translated_name")
    private String translatedName;

    @TableField("author")
    private String author;

    @TableField("cc_link")
    private String ccLink;

    @TableField("nexus_link")
    private String nexusLink;

    @TableField("remark")
    private String remark;

    @TableField("tags")
    private String tags;

    @TableLogic
    @TableField("deleted")
    private Boolean deleted = false;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
