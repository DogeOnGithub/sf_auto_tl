package com.starfield.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starfield.api.dto.PromptRequest;
import com.starfield.api.dto.PromptResponse;
import com.starfield.api.service.PromptService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
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

    /**
     * 查询默认 Prompt 应返回 200 和默认内容
     */
    @Test
    void getCurrentPrompt_default_returns200() throws Exception {
        when(promptService.getCurrentPrompt())
                .thenReturn(new PromptResponse(PromptService.DEFAULT_PROMPT, false));

        mockMvc.perform(get("/api/prompts/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(PromptService.DEFAULT_PROMPT))
                .andExpect(jsonPath("$.isCustom").value(false));
    }

    /**
     * 查询自定义 Prompt 应返回 200 和自定义内容
     */
    @Test
    void getCurrentPrompt_custom_returns200() throws Exception {
        when(promptService.getCurrentPrompt())
                .thenReturn(new PromptResponse("自定义翻译指令", true));

        mockMvc.perform(get("/api/prompts/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("自定义翻译指令"))
                .andExpect(jsonPath("$.isCustom").value(true));
    }

    /**
     * 保存 Prompt 应返回 200 和保存后的内容
     */
    @Test
    void savePrompt_validContent_returns200() throws Exception {
        when(promptService.savePrompt(any(PromptRequest.class)))
                .thenReturn(new PromptResponse("新 Prompt", true));

        mockMvc.perform(put("/api/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromptRequest("新 Prompt"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("新 Prompt"))
                .andExpect(jsonPath("$.isCustom").value(true));
    }

    /**
     * Prompt 过长应返回 400
     */
    @Test
    void savePrompt_tooLong_returns400() throws Exception {
        when(promptService.savePrompt(any(PromptRequest.class)))
                .thenThrow(new PromptService.PromptTooLongException(10000));

        mockMvc.perform(put("/api/prompts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PromptRequest("太长了"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("PROMPT_TOO_LONG"))
                .andExpect(jsonPath("$.message").value("Prompt 内容超过最大长度限制"));
    }

    /**
     * 恢复默认 Prompt 应返回 200 和默认内容
     */
    @Test
    void resetPrompt_returns200WithDefault() throws Exception {
        when(promptService.resetPrompt())
                .thenReturn(new PromptResponse(PromptService.DEFAULT_PROMPT, false));

        mockMvc.perform(delete("/api/prompts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value(PromptService.DEFAULT_PROMPT))
                .andExpect(jsonPath("$.isCustom").value(false));
    }
}
