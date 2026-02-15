-- 翻译任务同步失败计数
ALTER TABLE translation_task ADD COLUMN sync_fail_count INT NOT NULL DEFAULT 0;
