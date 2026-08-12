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

    @TableField(value = "creation_version_id", updateStrategy = FieldStrategy.ALWAYS)
    private Long creationVersionId;

    @TableField("prompt_id")
    private Long promptId;

    @TableField("confirmation_mode")
    private String confirmationMode = "direct";

    @TableField("source_type")
    private String sourceType = "esm";

    @TableField("llm_base_url")
    private String llmBaseUrl;

    @TableField("llm_model")
    private String llmModel;

    @TableField("sync_fail_count")
    private Integer syncFailCount = 0;

    /**
     * 失败原因分类 目前只有 sync_timeout（连续同步失败被判死）
     * <p>用 ALWAYS 策略是为了任务被复活时能把它清成 null 默认的 NOT_NULL 策略写不进 null
     */
    @TableField(value = "failed_reason", updateStrategy = FieldStrategy.ALWAYS)
    private String failedReason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
