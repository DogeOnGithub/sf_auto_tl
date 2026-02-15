package com.starfield.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.starfield.api.dto.DictionaryEntriesResponse;
import com.starfield.api.dto.DictionaryEntryRequest;
import com.starfield.api.dto.DictionaryEntryResponse;
import com.starfield.api.service.DictionaryService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DictionaryController.class)
class DictionaryControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    DictionaryService dictionaryService;

    /**
     * 查询全部词条应返回 200 和词条列表
     */
    @Test
    void getEntries_noKeyword_returns200() throws Exception {
        var entries = List.of(new DictionaryEntryResponse(1L, "Dragon", "龙"));
        when(dictionaryService.getEntries(null))
                .thenReturn(new DictionaryEntriesResponse(entries));

        mockMvc.perform(get("/api/dictionary/entries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").isArray())
                .andExpect(jsonPath("$.entries[0].id").value(1))
                .andExpect(jsonPath("$.entries[0].sourceText").value("Dragon"))
                .andExpect(jsonPath("$.entries[0].targetText").value("龙"));
    }

    /**
     * 带关键词查询应返回 200 和过滤后的词条
     */
    @Test
    void getEntries_withKeyword_returns200() throws Exception {
        var entries = List.of(new DictionaryEntryResponse(1L, "Dragon", "龙"));
        when(dictionaryService.getEntries("Dragon"))
                .thenReturn(new DictionaryEntriesResponse(entries));

        mockMvc.perform(get("/api/dictionary/entries").param("keyword", "Dragon"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries").hasJsonPath())
                .andExpect(jsonPath("$.entries[0].sourceText").value("Dragon"));
    }

    /**
     * 创建词条应返回 200 和词条响应
     */
    @Test
    void createEntry_validRequest_returns200() throws Exception {
        when(dictionaryService.createEntry(any(DictionaryEntryRequest.class)))
                .thenReturn(new DictionaryEntryResponse(1L, "Sword", "剑"));

        mockMvc.perform(post("/api/dictionary/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DictionaryEntryRequest("Sword", "剑"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sourceText").value("Sword"))
                .andExpect(jsonPath("$.targetText").value("剑"));
    }

    /**
     * 词条内容为空应返回 400
     */
    @Test
    void createEntry_emptyContent_returns400() throws Exception {
        when(dictionaryService.createEntry(any(DictionaryEntryRequest.class)))
                .thenThrow(new DictionaryService.EmptyEntryException());

        mockMvc.perform(post("/api/dictionary/entries")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DictionaryEntryRequest("", ""))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("EMPTY_ENTRY"))
                .andExpect(jsonPath("$.message").value("词条原文和译文不能为空"));
    }

    /**
     * 更新词条应返回 200 和更新后的词条
     */
    @Test
    void updateEntry_validRequest_returns200() throws Exception {
        when(dictionaryService.updateEntry(eq(1L), any(DictionaryEntryRequest.class)))
                .thenReturn(new DictionaryEntryResponse(1L, "Shield", "盾"));

        mockMvc.perform(put("/api/dictionary/entries/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DictionaryEntryRequest("Shield", "盾"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sourceText").value("Shield"))
                .andExpect(jsonPath("$.targetText").value("盾"));
    }

    /**
     * 更新不存在的词条应返回 404
     */
    @Test
    void updateEntry_notFound_returns404() throws Exception {
        when(dictionaryService.updateEntry(eq(99L), any(DictionaryEntryRequest.class)))
                .thenThrow(new DictionaryService.EntryNotFoundException(99L));

        mockMvc.perform(put("/api/dictionary/entries/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DictionaryEntryRequest("a", "b"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTRY_NOT_FOUND"));
    }

    /**
     * 删除词条应返回 204
     */
    @Test
    void deleteEntry_existingId_returns204() throws Exception {
        doNothing().when(dictionaryService).deleteEntry(1L);

        mockMvc.perform(delete("/api/dictionary/entries/1"))
                .andExpect(status().isNoContent());
    }

    /**
     * 删除不存在的词条应返回 404
     */
    @Test
    void deleteEntry_notFound_returns404() throws Exception {
        org.mockito.Mockito.doThrow(new DictionaryService.EntryNotFoundException(99L))
                .when(dictionaryService).deleteEntry(99L);

        mockMvc.perform(delete("/api/dictionary/entries/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ENTRY_NOT_FOUND"));
    }
}
