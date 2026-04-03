-- 翻译任务记录使用的 LLM 配置（URL 和模型名，不记录 API Key）
ALTER TABLE translation_task ADD COLUMN llm_base_url TEXT;
ALTER TABLE translation_task ADD COLUMN llm_model TEXT;
