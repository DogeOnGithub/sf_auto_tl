package com.starfield.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 安全网定时任务 每 30 秒查询活跃任务向 Engine 同步进度
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskProgressScheduler {

    final TaskService taskService;

    /**
     * 每 30 秒查询活跃任务 向 Engine 同步进度作为安全网
     */
    @Scheduled(fixedDelay = 30000)
    public void syncActiveTasks() {
        taskService.syncActiveTasksFromEngine();
    }

    /**
     * 每小时检查 uploads 目录大小 超过 20GB 时清理已完成和已失败任务的文件
     */
    @Scheduled(fixedDelay = 3600000)
    public void cleanupUploads() {
        taskService.cleanupUploadsIfOversized();
    }
}
