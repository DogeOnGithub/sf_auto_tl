package com.starfield.api.controller;

import com.starfield.api.dto.ProgressCallbackRequest;
import com.starfield.api.dto.TaskResponse;
import com.starfield.api.service.TaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    /**
     * 接收 Engine 的进度回调
     *
     * @param taskId  任务 ID
     * @param request 进度回调请求
     * @return 200 OK
     */
    @PostMapping("/{taskId}/progress")
    public ResponseEntity<Void> receiveProgress(@PathVariable String taskId,
                                                @RequestBody ProgressCallbackRequest request) {
        log.info("[receiveProgress] 收到进度回调 taskId {}", taskId);
        taskService.handleProgressCallback(taskId, request);
        return ResponseEntity.ok().build();
    }


}
