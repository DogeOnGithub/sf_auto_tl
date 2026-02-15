package com.starfield.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starfield.api.dto.PromptDetailResponse;
import com.starfield.api.dto.PromptDetailResponse.TaskBriefInfo;
import com.starfield.api.dto.PromptListResponse;
import com.starfield.api.dto.PromptRequest;
import com.starfield.api.service.PromptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PromptController.class)
class PromptControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    PromptService promptService;

    static final LocalDateTime NOW = LocalDateTime.of(2024, 1, 1, 0, 0);

    /**
     * POST /api/prompts 创建成功应返回 200
     */
    @Test
    void createPrompt_returns200() throws Exception {
        when(promptService.createPrompt(any(PromptRequest.class)))
                .thenReturn(new PromptListResponse(1L, "测试模板", "测试内容", 0, NOW, NOW));

        mockMvc.perform(post("/api/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromptRequest("测试模板", "测试内容"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("测试模板"))
                .andExpect(jsonPath("$.content").value("测试内容"))
                .andExpect(jsonPath("$.usageCount").value(0));
    }

    /**
     * POST /api/prompts 校验失败应返回 400
     */
    @Test
    void createPrompt_validationError_returns400() throws Exception {
        when(promptService.createPrompt(any(PromptRequest.class)))
                .thenThrow(new PromptService.PromptValidationException("Prompt 名称不能为空"));

        mockMvc.perform(post("/api/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromptRequest("", "内容"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PROMPT_VALIDATION_ERROR"));
    }

    /**
     * GET /api/prompts 应返回 200 和列表
     */
    @Test
    void listPrompts_returns200() throws Exception {
        when(promptService.listPrompts())
                .thenReturn(List.of(
                        new PromptListResponse(1L, "模板A", "内容A", 3, NOW, NOW),
                        new PromptListResponse(2L, "模板B", "内容B", 1, NOW, NOW)
                ));

        mockMvc.perform(get("/api/prompts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("模板A"))
                .andExpect(jsonPath("$[0].usageCount").value(3))
                .andExpect(jsonPath("$[1].name").value("模板B"));
    }

    /**
     * GET /api/prompts/{id} 应返回 200 和详情
     */
    @Test
    void getPromptDetail_returns200() throws Exception {
        var tasks = List.of(new TaskBriefInfo("task-1", "test.esm", "COMPLETED", NOW));
        when(promptService.getPromptDetail(1L))
                .thenReturn(new PromptDetailResponse(1L, "模板A", "内容A", 1, tasks, NOW, NOW));

        mockMvc.perform(get("/api/prompts/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("模板A"))
                .andExpect(jsonPath("$.usageCount").value(1))
                .andExpect(jsonPath("$.tasks.length()").value(1))
                .andExpect(jsonPath("$.tasks[0].taskId").value("task-1"));
    }

    /**
     * GET /api/prompts/{id} 不存在应返回 404
     */
    @Test
    void getPromptDetail_notFound_returns404() throws Exception {
        when(promptService.getPromptDetail(999L))
                .thenThrow(new PromptService.PromptNotFoundException(999L));

        mockMvc.perform(get("/api/prompts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PROMPT_NOT_FOUND"));
    }

    /**
     * PUT /api/prompts/{id} 更新成功应返回 200
     */
    @Test
    void updatePrompt_returns200() throws Exception {
        when(promptService.updatePrompt(eq(1L), any(PromptRequest.class)))
                .thenReturn(new PromptListResponse(1L, "更新名称", "更新内容", 2, NOW, NOW));

        mockMvc.perform(put("/api/prompts/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromptRequest("更新名称", "更新内容"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("更新名称"))
                .andExpect(jsonPath("$.content").value("更新内容"));
    }

    /**
     * PUT /api/prompts/{id} 不存在应返回 404
     */
    @Test
    void updatePrompt_notFound_returns404() throws Exception {
        when(promptService.updatePrompt(eq(999L), any(PromptRequest.class)))
                .thenThrow(new PromptService.PromptNotFoundException(999L));

        mockMvc.perform(put("/api/prompts/999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromptRequest("名称", "内容"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PROMPT_NOT_FOUND"));
    }

    /**
     * DELETE /api/prompts/{id} 删除成功应返回 204
     */
    @Test
    void deletePrompt_returns204() throws Exception {
        doNothing().when(promptService).deletePrompt(1L);

        mockMvc.perform(delete("/api/prompts/1"))
                .andExpect(status().isNoContent());
    }

    /**
     * DELETE /api/prompts/{id} 不存在应返回 404
     */
    @Test
    void deletePrompt_notFound_returns404() throws Exception {
        doThrow(new PromptService.PromptNotFoundException(999L))
                .when(promptService).deletePrompt(999L);

        mockMvc.perform(delete("/api/prompts/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("PROMPT_NOT_FOUND"));
    }
}
