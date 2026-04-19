-- Creation 推荐与警告功能

-- 1. Creation 表新增推荐字段
ALTER TABLE creation ADD COLUMN featured BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE creation ADD COLUMN featured_at TIMESTAMP;

-- 2. 新建警告记录表
CREATE TABLE creation_warning (
    id            BIGSERIAL    NOT NULL PRIMARY KEY,
    creation_id   BIGINT       NOT NULL REFERENCES creation(id),
    content       TEXT         NOT NULL,
    status        TEXT         NOT NULL DEFAULT 'UNRESOLVED',
    created_at    TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT now()
);

CREATE INDEX idx_creation_warning_creation_id ON creation_warning(creation_id);
