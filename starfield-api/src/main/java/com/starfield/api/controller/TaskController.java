package com.starfield.api.controller;

import com.starfield.api.dto.TaskResponse;
import com.starfield.api.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 任务管理控制器
 */
@Slf4j
@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class TaskController {

    final TaskService taskService;

    /**
     * 查询翻译任务状态和进度
     *
     * @param taskId 任务 ID
     * @return 任务状态响应
     */
    @GetMapping("/{taskId}")
    public ResponseEntity<TaskResponse> getTask(@PathVariable String taskId) {
        log.info("[getTask] 收到任务查询请求 taskId {}", taskId);
        var response = taskService.getTask(taskId);
        return ResponseEntity.ok(response);
    }

    /**
     * 查询所有翻译任务列表
     *
     * @return 任务列表
     */
    @GetMapping
    public ResponseEntity<java.util.List<TaskResponse>> listTasks() {
        log.info("[listTasks] 收到任务列表查询请求");
        var tasks = taskService.listTasks();
        return ResponseEntity.ok(tasks);
    }

}
