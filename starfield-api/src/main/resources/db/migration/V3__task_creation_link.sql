-- 翻译任务关联 creation 版本
ALTER TABLE translation_task ADD COLUMN creation_version_id BIGINT;
CREATE INDEX idx_task_creation_version ON translation_task(creation_version_id);
