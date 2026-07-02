-- 支持按作品最新版本添加时间排序（MAX(created_at) per creation_id）的复合索引
CREATE INDEX idx_creation_version_creation_id_created_at ON creation_version(creation_id, created_at);
