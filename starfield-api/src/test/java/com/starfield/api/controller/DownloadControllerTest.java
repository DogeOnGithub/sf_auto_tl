package com.starfield.api.controller;

import com.starfield.api.dto.DownloadResponse;
import com.starfield.api.service.DownloadService;
import com.starfield.api.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(DownloadController.class)
class DownloadControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    DownloadService downloadService;

    /** 下载请求应返回 200 和 JSON 响应 */
    @Test
    void download_completed_returns200WithJson() throws Exception {
        var response = new DownloadResponse(
                "https://cos.example.com/translations/task-1/StarfieldMod.zip",
                "StarfieldMod.zip");
        when(downloadService.getDownloadFile("task-1")).thenReturn(response);

        mockMvc.perform(get("/api/tasks/task-1/download"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downloadUrl").value("https://cos.example.com/translations/task-1/StarfieldMod.zip"))
                .andExpect(jsonPath("$.fileName").value("StarfieldMod.zip"));
    }

    /** 任务不存在应返回 404 */
    @Test
    void download_taskNotFound_returns404() throws Exception {
        when(downloadService.getDownloadFile("not-exist"))
                .thenThrow(new TaskService.TaskNotFoundException("not-exist"));

        mockMvc.perform(get("/api/tasks/not-exist/download"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("翻译任务不存在"));
    }

    /** 任务未完成应返回 409 */
    @Test
    void download_taskNotCompleted_returns409() throws Exception {
        when(downloadService.getDownloadFile("task-2"))
                .thenThrow(new DownloadService.TaskNotCompletedException("task-2"));

        mockMvc.perform(get("/api/tasks/task-2/download"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TASK_NOT_COMPLETED"))
                .andExpect(jsonPath("$.message").value("翻译任务尚未完成"));
    }

    /** 下载链接为空应返回 404 */
    @Test
    void download_downloadUrlEmpty_returns404() throws Exception {
        when(downloadService.getDownloadFile("task-3"))
                .thenThrow(new DownloadService.DownloadUrlEmptyException("task-3"));

        mockMvc.perform(get("/api/tasks/task-3/download"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DOWNLOAD_URL_EMPTY"))
                .andExpect(jsonPath("$.message").value("下载链接为空"));
    }
}
