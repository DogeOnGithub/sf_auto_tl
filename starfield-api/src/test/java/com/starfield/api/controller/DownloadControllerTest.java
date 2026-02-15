package com.starfield.api.controller;

import com.starfield.api.service.DownloadService;
import com.starfield.api.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.ByteArrayResource;
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

    /**
     * 下载翻译文件应返回 200 和正确的 Content-Disposition
     */
    @Test
    void download_translated_returns200WithFile() throws Exception {
        var resource = new ByteArrayResource("translated content".getBytes());
        var result = new DownloadService.DownloadResult(resource, "StarfieldMod_zh-CN.esm");
        when(downloadService.getDownloadFile("task-1", "translated")).thenReturn(result);

        mockMvc.perform(get("/api/tasks/task-1/download").param("type", "translated"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''StarfieldMod_zh-CN.esm"))
                .andExpect(content().bytes("translated content".getBytes()));
    }

    /**
     * 下载原始文件应返回 200 和原始文件名
     */
    @Test
    void download_original_returns200WithOriginalFile() throws Exception {
        var resource = new ByteArrayResource("original content".getBytes());
        var result = new DownloadService.DownloadResult(resource, "StarfieldMod.esm");
        when(downloadService.getDownloadFile("task-2", "original")).thenReturn(result);

        mockMvc.perform(get("/api/tasks/task-2/download").param("type", "original"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''StarfieldMod.esm"))
                .andExpect(content().bytes("original content".getBytes()));
    }

    /**
     * 不传 type 参数应默认为 translated
     */
    @Test
    void download_noTypeParam_defaultsToTranslated() throws Exception {
        var resource = new ByteArrayResource("content".getBytes());
        var result = new DownloadService.DownloadResult(resource, "Mod_zh-CN.esm");
        when(downloadService.getDownloadFile("task-3", "translated")).thenReturn(result);

        mockMvc.perform(get("/api/tasks/task-3/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename*=UTF-8''Mod_zh-CN.esm"));
    }

    /**
     * 任务不存在应返回 404
     */
    @Test
    void download_taskNotFound_returns404() throws Exception {
        when(downloadService.getDownloadFile("not-exist", "translated"))
                .thenThrow(new TaskService.TaskNotFoundException("not-exist"));

        mockMvc.perform(get("/api/tasks/not-exist/download"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TASK_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("翻译任务不存在"));
    }

    /**
     * 任务未完成应返回 409
     */
    @Test
    void download_taskNotCompleted_returns409() throws Exception {
        when(downloadService.getDownloadFile("task-4", "translated"))
                .thenThrow(new DownloadService.TaskNotCompletedException("task-4"));

        mockMvc.perform(get("/api/tasks/task-4/download"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TASK_NOT_COMPLETED"))
                .andExpect(jsonPath("$.message").value("翻译任务尚未完成"));
    }

    /**
     * 文件不存在应返回 404
     */
    @Test
    void download_fileNotFound_returns404() throws Exception {
        when(downloadService.getDownloadFile("task-5", "translated"))
                .thenThrow(new DownloadService.FileNotFoundException("task-5", "/path"));

        mockMvc.perform(get("/api/tasks/task-5/download"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FILE_NOT_FOUND"));
    }
}
