-- 翻译任务来源类型：esm（默认，直接翻译 ESM/ESP）或 strings（开启本地化的 mod，翻译外部 Strings 文件）
ALTER TABLE translation_task
    ADD COLUMN source_type TEXT NOT NULL DEFAULT 'esm';
