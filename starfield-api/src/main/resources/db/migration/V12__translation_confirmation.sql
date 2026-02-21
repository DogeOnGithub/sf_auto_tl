-- 翻译确认记录表
CREATE TABLE translation_confirmation (
    id               BIGSERIAL    NOT NULL PRIMARY KEY,
    task_id          VARCHAR(36)  NOT NULL,
    record_id        VARCHAR(100) NOT NULL,
    record_type      VARCHAR(50)  NOT NULL,
    source_text      TEXT         NOT NULL,
    target_text      TEXT         NOT NULL,
    status           VARCHAR(20)  NOT NULL DEFAULT 'pending',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_confirmation_task_id ON translation_confirmation (task_id);
CREATE INDEX idx_confirmation_status ON translation_confirmation (task_id, status);

-- 翻译任务表新增确认模式字段
ALTER TABLE translation_task ADD COLUMN confirmation_mode VARCHAR(20) NOT NULL DEFAULT 'direct';
