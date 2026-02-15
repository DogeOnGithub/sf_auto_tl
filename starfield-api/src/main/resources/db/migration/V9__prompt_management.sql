-- 多 Prompt 模板管理：改造 custom_prompt 表，translation_task 表增加 prompt_id

-- custom_prompt 表增加 name 字段
ALTER TABLE custom_prompt ADD COLUMN name VARCHAR(255);

-- 为已有数据设置默认名称
UPDATE custom_prompt SET name = '默认自定义 Prompt' WHERE name IS NULL;

-- 设置 name 为 NOT NULL
ALTER TABLE custom_prompt ALTER COLUMN name SET NOT NULL;

-- custom_prompt 表增加 deleted 字段（软删除）
ALTER TABLE custom_prompt ADD COLUMN deleted BOOLEAN NOT NULL DEFAULT FALSE;

-- 移除 is_active 字段（不再需要单 Prompt 激活机制）
ALTER TABLE custom_prompt DROP COLUMN is_active;

-- translation_task 表增加 prompt_id 字段，记录任务使用的 Prompt 模板
ALTER TABLE translation_task ADD COLUMN prompt_id BIGINT;
