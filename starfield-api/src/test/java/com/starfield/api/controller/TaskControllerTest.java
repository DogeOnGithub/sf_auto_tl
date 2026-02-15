package com.starfield.api.controller;

import com.starfield.api.dto.TaskResponse;
import com.starfield.api.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TaskController.class)
class TaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TaskService taskService;

    /**
     * 查询存在的任务应返回 200 和完整任务信息
     */
    @Test
    void getTask_existingTask_returns200WithTaskInfo() throws Exception {
        var now = LocalDateTime.of(2024, 1, 15, 10, 30, 0);
        var response = new TaskResponse("task-123", "test.esm", "translating",
                new TaskResponse.Progress(5, 10), null, now, now);
        when(taskService.getTask("task-123")).thenReturn(response);

        mockMvc.perform(get("/api/tasks/task-123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-123"))
                .andExpect(jsonPath("$.fileName").value("test.esm"))
                .andExpect(jsonPath("$.status").value("translating"))
                .andExpect(jsonPath("$.progress.translated").value(5))
                .andExpect(jsonPath("$.progress.total").value(10))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    /**
     * 查询不存在的任务应返回 404
     */
    @Test
    void getTask_nonExistentTask_returns404() throws Exception {
        when(taskService.getTask("not-exist")).thenThrow(new TaskService.TaskNotFoundException("not-exist"));

        mockMvc.perform(get("/api/tasks/not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("翻译任务不存在"));
    }
}
