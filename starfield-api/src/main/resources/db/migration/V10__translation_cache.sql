CREATE TABLE translation_cache (
    id               BIGSERIAL    NOT NULL PRIMARY KEY,
    task_id          VARCHAR(36)  NOT NULL,
    subrecord_type   VARCHAR(10)  NOT NULL,
    source_text      TEXT         NOT NULL,
    target_text      TEXT         NOT NULL,
    target_lang      VARCHAR(20)  NOT NULL DEFAULT 'zh-CN',
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_cache_lookup
    ON translation_cache (subrecord_type, source_text, target_lang);
