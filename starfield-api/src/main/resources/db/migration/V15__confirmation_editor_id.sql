-- 翻译确认记录表新增 editor_id 字段（ESM 记录的 Editor ID）
ALTER TABLE translation_confirmation ADD COLUMN editor_id VARCHAR(200) DEFAULT '';
