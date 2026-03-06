-- 为 translation_cache 添加 source_text_hash 字段，解决超长 source_text 导致 btree 索引溢出问题
-- PostgreSQL btree 索引单行上限 2704 字节，超长文本直接参与索引会报错

-- 添加 hash 字段
ALTER TABLE translation_cache ADD COLUMN source_text_hash VARCHAR(32);

-- 回填已有数据的 hash 值
UPDATE translation_cache SET source_text_hash = MD5(source_text);

-- 设置非空约束
ALTER TABLE translation_cache ALTER COLUMN source_text_hash SET NOT NULL;

-- 删除旧的唯一索引（source_text 直接参与）
DROP INDEX IF EXISTS uk_cache_lookup;

-- 创建新的唯一索引（用 hash 替代 source_text）
CREATE UNIQUE INDEX uk_cache_lookup ON translation_cache (record_type, subrecord_type, source_text_hash, target_lang);
