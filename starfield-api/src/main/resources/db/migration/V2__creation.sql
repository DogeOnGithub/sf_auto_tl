-- Mod 作品表（基本信息）
CREATE TABLE creation (
    id                BIGSERIAL    NOT NULL PRIMARY KEY,
    name              VARCHAR(255) NOT NULL,
    translated_name   VARCHAR(255),
    author            VARCHAR(255),
    cc_link           VARCHAR(1000),
    nexus_link        VARCHAR(1000),
    remark            TEXT,
    tags              VARCHAR(1000),
    deleted           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Mod 作品版本表
CREATE TABLE creation_version (
    id              BIGSERIAL    NOT NULL PRIMARY KEY,
    creation_id     BIGINT       NOT NULL,
    version         VARCHAR(50)  NOT NULL,
    file_path       VARCHAR(500),
    file_share_link VARCHAR(1000),
    deleted         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uk_creation_version ON creation_version(creation_id, version) WHERE deleted = false;
CREATE INDEX idx_creation_version_creation_id ON creation_version(creation_id);

-- Mod 作品图片表
CREATE TABLE creation_image (
    id            BIGSERIAL    NOT NULL PRIMARY KEY,
    creation_id   BIGINT       NOT NULL,
    image_path    VARCHAR(500) NOT NULL,
    sort_order    INT          DEFAULT 0,
    deleted       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_creation_image_creation_id ON creation_image(creation_id);
