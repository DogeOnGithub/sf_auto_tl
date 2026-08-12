package com.starfield.api.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 安全网定时任务 按 engine.sync.interval-ms 的间隔查询活跃任务向 Engine 同步进度
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TaskProgressScheduler {

    final TaskService taskService;

    /**
     * 按 engine.sync.interval-ms 的间隔查询活跃任务 向 Engine 同步进度作为安全网
     * <p>间隔与 TaskService 判定失败的次数阈值同源于一个配置项 不要在这里写死数值
     */
    @Scheduled(fixedDelayString = "${engine.sync.interval-ms:30000}")
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

    /**
     * 每天凌晨 3 点清理过期任务的 COS 文件
     */
    @Scheduled(cron = "0 0 3 * * ?")
    public void cleanupExpiredTasks() {
        taskService.cleanupExpiredTasks();
    }
}
