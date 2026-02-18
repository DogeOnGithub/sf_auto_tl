ALTER TABLE translation_cache ADD COLUMN record_type VARCHAR(10);

-- 从已有数据中无法推断 record_type，设为空字符串作为默认值
UPDATE translation_cache SET record_type = '' WHERE record_type IS NULL;

ALTER TABLE translation_cache ALTER COLUMN record_type SET NOT NULL;
ALTER TABLE translation_cache ALTER COLUMN record_type SET DEFAULT '';

-- 重建唯一索引，加入 record_type
DROP INDEX IF EXISTS uk_cache_lookup;
CREATE UNIQUE INDEX uk_cache_lookup
    ON translation_cache (record_type, subrecord_type, source_text, target_lang);
