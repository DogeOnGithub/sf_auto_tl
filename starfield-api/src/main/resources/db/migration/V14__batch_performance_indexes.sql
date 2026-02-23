-- 确认记录表增加唯一索引 防止重复插入相同 task_id + record_id
CREATE UNIQUE INDEX IF NOT EXISTS uk_confirmation_task_record
    ON translation_confirmation (task_id, record_id);
