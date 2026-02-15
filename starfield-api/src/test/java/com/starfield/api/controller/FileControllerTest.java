package com.starfield.api.controller;

import com.starfield.api.dto.FileUploadResponse;
import com.starfield.api.service.FileUploadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FileController.class)
class FileControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    FileUploadService fileUploadService;

    /**
     * 有效文件上传应返回 200 和 taskId
     */
    @Test
    void upload_validFile_returns200WithTaskId() throws Exception {
        var response = new FileUploadResponse("task-123", "test.esm");
        when(fileUploadService.upload(any(), isNull())).thenReturn(response);

        var file = new MockMultipartFile("file", "test.esm", "application/octet-stream", "TES4data".getBytes());

        mockMvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value("task-123"))
                .andExpect(jsonPath("$.fileName").value("test.esm"));
    }

    /**
     * 无效 ESM 格式应返回 400
     */
    @Test
    void upload_invalidFormat_returns400() throws Exception {
        when(fileUploadService.upload(any(), isNull())).thenThrow(new FileUploadService.InvalidEsmFormatException());

        var file = new MockMultipartFile("file", "test.txt", "text/plain", "hello".getBytes());

        mockMvc.perform(multipart("/api/files/upload").file(file))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_FORMAT"))
                .andExpect(jsonPath("$.message").value("文件不是有效的 ESM 格式"));
    }
}
