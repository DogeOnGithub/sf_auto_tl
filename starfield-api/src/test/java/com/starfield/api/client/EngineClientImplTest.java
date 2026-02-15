package com.starfield.api.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EngineClientImplTest {

    @Mock
    RestTemplate restTemplate;

    EngineClientImpl engineClient;

    static final String BASE_URL = "http://localhost:5000";

    @BeforeEach
    void setUp() {
        engineClient = new EngineClientImpl(restTemplate, BASE_URL);
    }

    @Test
    void submitTranslation_success() {
        var request = new EngineClient.EngineTranslateRequest(
                "task-1", "/path/to/file.esm", "zh-CN", null, List.of()
        );
        var expected = new EngineClient.EngineTranslateResponse("task-1", "accepted");
        when(restTemplate.postForObject(
                eq(BASE_URL + "/engine/translate"), eq(request), eq(EngineClient.EngineTranslateResponse.class)
        )).thenReturn(expected);

        var result = engineClient.submitTranslation(request);

        assertThat(result).isEqualTo(expected);
    }

    @Test
    void submitTranslation_withDictionaryEntries() {
        var entries = List.of(
                new EngineClient.DictionaryEntryDto("Dragon", "龙"),
                new EngineClient.DictionaryEntryDto("Sword", "剑")
        );
        var request = new EngineClient.EngineTranslateRequest(
                "task-2", "/path/to/mod.esm", "zh-CN", "翻译为中文", entries
        );
        var expected = new EngineClient.EngineTranslateResponse("task-2", "accepted");
        when(restTemplate.postForObject(
                eq(BASE_URL + "/engine/translate"), eq(request), eq(EngineClient.EngineTranslateResponse.class)
        )).thenReturn(expected);

        var result = engineClient.submitTranslation(request);

        assertThat(result).isEqualTo(expected);
        assertThat(result.taskId()).isEqualTo("task-2");
    }

    @Test
    void submitTranslation_engineUnavailable_throwsException() {
        var request = new EngineClient.EngineTranslateRequest(
                "task-3", "/path/to/file.esm", "zh-CN", null, List.of()
        );
        when(restTemplate.postForObject(
                eq(BASE_URL + "/engine/translate"), any(), eq(EngineClient.EngineTranslateResponse.class)
        )).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> engineClient.submitTranslation(request))
                .isInstanceOf(EngineUnavailableException.class)
                .hasMessageContaining("翻译引擎不可用");
    }

    @Test
    void submitTranslation_unexpectedError_throwsException() {
        var request = new EngineClient.EngineTranslateRequest(
                "task-4", "/path/to/file.esm", "zh-CN", null, List.of()
        );
        when(restTemplate.postForObject(
                eq(BASE_URL + "/engine/translate"), any(), eq(EngineClient.EngineTranslateResponse.class)
        )).thenThrow(new RuntimeException("unexpected error"));

        assertThatThrownBy(() -> engineClient.submitTranslation(request))
                .isInstanceOf(EngineUnavailableException.class)
                .hasMessageContaining("调用翻译引擎异常");
    }

    @Test
    void getTaskStatus_success() {
        var expected = new EngineClient.EngineTaskStatusResponse(
                "task-1", "translating",
                new EngineClient.EngineProgress(5, 10),
                null, null, null
        );
        when(restTemplate.getForObject(
                eq(BASE_URL + "/engine/tasks/task-1"), eq(EngineClient.EngineTaskStatusResponse.class)
        )).thenReturn(expected);

        var result = engineClient.getTaskStatus("task-1");

        assertThat(result).isEqualTo(expected);
        assertThat(result.progress().translated()).isEqualTo(5);
        assertThat(result.progress().total()).isEqualTo(10);
    }

    @Test
    void getTaskStatus_completed_withOutputPath() {
        var expected = new EngineClient.EngineTaskStatusResponse(
                "task-5", "completed",
                new EngineClient.EngineProgress(10, 10),
                "/output/mod_zh-CN.esm", "/backup/mod_backup.esm", null
        );
        when(restTemplate.getForObject(
                eq(BASE_URL + "/engine/tasks/task-5"), eq(EngineClient.EngineTaskStatusResponse.class)
        )).thenReturn(expected);

        var result = engineClient.getTaskStatus("task-5");

        assertThat(result.status()).isEqualTo("completed");
        assertThat(result.outputFilePath()).isEqualTo("/output/mod_zh-CN.esm");
    }

    @Test
    void getTaskStatus_engineUnavailable_throwsException() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/engine/tasks/task-6"), eq(EngineClient.EngineTaskStatusResponse.class)
        )).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatThrownBy(() -> engineClient.getTaskStatus("task-6"))
                .isInstanceOf(EngineUnavailableException.class)
                .hasMessageContaining("翻译引擎不可用");
    }

    @Test
    void getTaskStatus_unexpectedError_throwsException() {
        when(restTemplate.getForObject(
                eq(BASE_URL + "/engine/tasks/task-7"), eq(EngineClient.EngineTaskStatusResponse.class)
        )).thenThrow(new RuntimeException("server error"));

        assertThatThrownBy(() -> engineClient.getTaskStatus("task-7"))
                .isInstanceOf(EngineUnavailableException.class)
                .hasMessageContaining("查询任务状态异常");
    }
}
