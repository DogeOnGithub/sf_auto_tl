-- 翻译任务表
CREATE TABLE translation_task (
    task_id              VARCHAR(36)  NOT NULL PRIMARY KEY,
    file_name            VARCHAR(255) NOT NULL,
    file_path            VARCHAR(500),
    original_backup_path VARCHAR(500),
    output_file_path     VARCHAR(500),
    status               VARCHAR(20)  NOT NULL DEFAULT 'waiting',
    translated_count     INT          DEFAULT 0,
    total_count          INT          DEFAULT 0,
    target_lang          VARCHAR(20)  DEFAULT 'zh-CN',
    error_message        TEXT,
    created_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 自定义 Prompt 表
CREATE TABLE custom_prompt (
    id         BIGSERIAL    NOT NULL PRIMARY KEY,
    content    TEXT         NOT NULL,
    is_active  BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 词典词条表
CREATE TABLE dictionary_entry (
    id          BIGSERIAL    NOT NULL PRIMARY KEY,
    source_text VARCHAR(500) NOT NULL,
    target_text VARCHAR(500) NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_source_text UNIQUE (source_text)
);
