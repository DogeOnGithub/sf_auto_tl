-- 任务失败原因分类，用于区分「引擎真的报了失败」和「API 只是联系不上引擎」
-- sync_timeout：连续同步失败超过容忍时长被判死。这种失败是推测性的，引擎线程可能仍在运行，
-- 因此该原因的任务允许被后到的 completed 回调复活，本地文件也要延后回收。
ALTER TABLE translation_task
    ADD COLUMN failed_reason TEXT;
