package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Mod 作品版本实体
 */
/**
 * Mod 作品版本实体
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("creation_version")
public class CreationVersion {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("creation_id")
    private Long creationId;

    @TableField("version")
    private String version;

    @TableField("file_path")
    private String filePath;

    @TableField("file_name")
    private String fileName;

    @TableField("file_share_link")
    private String fileShareLink;

    @TableField("patch_file_path")
    private String patchFilePath;

    @TableField("patch_file_name")
    private String patchFileName;

    @TableLogic
    @TableField("deleted")
    private Boolean deleted = false;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
