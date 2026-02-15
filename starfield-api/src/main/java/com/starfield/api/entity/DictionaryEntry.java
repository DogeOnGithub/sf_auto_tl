package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 词典词条实体
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("dictionary_entry")
public class DictionaryEntry {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("source_text")
    private String sourceText;

    @TableField("target_text")
    private String targetText;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
