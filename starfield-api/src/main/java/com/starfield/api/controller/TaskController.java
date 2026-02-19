package com.starfield.api.controller;

import com.starfield.api.dto.ProgressCallbackRequest;
import com.starfield.api.dto.TaskPageResponse;
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
     * 分页查询翻译任务列表
     *
     * @param page 页码
     * @param size 每页大小
     * @return 分页响应
     */
    @GetMapping("/page")
    public ResponseEntity<TaskPageResponse> listTasksPaged(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        log.info("[listTasksPaged] 分页查询任务 page {} size {}", page, size);
        var result = taskService.listTasksPaged(page, size);
        return ResponseEntity.ok(result);
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

    /**
     * 手动清理翻译任务：删除 COS 文件并标记为 expired
     *
     * @param taskId 任务 ID
     * @return 200 OK
     */
    @PostMapping("/{taskId}/expire")
    public ResponseEntity<Void> expireTask(@PathVariable String taskId) {
        log.info("[expireTask] 收到手动清理请求 taskId {}", taskId);
        taskService.expireTask(taskId);
        return ResponseEntity.ok().build();
    }

    /**
     * 批量清理翻译任务
     *
     * @param taskIds 任务 ID 列表
     * @return 成功清理的数量
     */
    @PostMapping("/batch-expire")
    public ResponseEntity<java.util.Map<String, Integer>> batchExpireTasks(@RequestBody java.util.List<String> taskIds) {
        log.info("[batchExpireTasks] 收到批量清理请求 count {}", taskIds.size());
        var expiredCount = taskService.batchExpireTasks(taskIds);
        return ResponseEntity.ok(java.util.Map.of("expiredCount", expiredCount));
    }


}
