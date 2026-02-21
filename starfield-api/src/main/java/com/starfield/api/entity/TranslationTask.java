package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 翻译任务实体
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("translation_task")
public class TranslationTask {

    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;

    @TableField("file_name")
    private String fileName;

    @TableField("file_path")
    private String filePath;

    @TableField("original_backup_path")
    private String originalBackupPath;

    @TableField("output_file_path")
    private String outputFilePath;

    @TableField("status")
    private TaskStatus status = TaskStatus.waiting;

    @TableField("translated_count")
    private Integer translatedCount = 0;

    @TableField("total_count")
    private Integer totalCount = 0;

    @TableField("target_lang")
    private String targetLang = "zh-CN";

    @TableField("error_message")
    private String errorMessage;

    @TableField("download_url")
    private String downloadUrl;

    @TableField("creation_version_id")
    private Long creationVersionId;

    @TableField("prompt_id")
    private Long promptId;

    @TableField("confirmation_mode")
    private String confirmationMode = "direct";

    @TableField("sync_fail_count")
    private Integer syncFailCount = 0;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
