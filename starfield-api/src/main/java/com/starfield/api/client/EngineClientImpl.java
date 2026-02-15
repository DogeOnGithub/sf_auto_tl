package com.starfield.api.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

/**
 * EngineClient 的 HTTP 实现，封装对 Python 翻译引擎的 REST 调用
 */
@Slf4j
@Component
public class EngineClientImpl implements EngineClient {

    private final RestTemplate restTemplate;
    private final String engineBaseUrl;

    public EngineClientImpl(RestTemplate restTemplate,
                            @Value("${engine.base-url}") String engineBaseUrl) {
        this.restTemplate = restTemplate;
        this.engineBaseUrl = engineBaseUrl;
    }

    /**
     * 向翻译引擎提交翻译任务
     */
    @Override
    public EngineTranslateResponse submitTranslation(EngineTranslateRequest request) {
        var url = engineBaseUrl + "/engine/translate";
        log.info("[submitTranslation] 提交翻译任务 taskId {}", request.taskId());
        try {
            var response = restTemplate.postForObject(url, request, EngineTranslateResponse.class);
            log.info("[submitTranslation] 翻译任务已提交 taskId {}", request.taskId());
            return response;
        } catch (ResourceAccessException e) {
            log.error("[submitTranslation] 翻译引擎不可用 url {}", url, e);
            throw new EngineUnavailableException("翻译引擎不可用", e);
        } catch (Exception e) {
            log.error("[submitTranslation] 调用翻译引擎异常 taskId {}", request.taskId(), e);
            throw new EngineUnavailableException("调用翻译引擎异常", e);
        }
    }

    /**
     * 查询翻译引擎中的任务状态和进度
     */
    @Override
    public EngineTaskStatusResponse getTaskStatus(String taskId) {
        var url = engineBaseUrl + "/engine/tasks/" + taskId;
        log.info("[getTaskStatus] 查询任务状态 taskId {}", taskId);
        try {
            var response = restTemplate.getForObject(url, EngineTaskStatusResponse.class);
            log.info("[getTaskStatus] 任务状态查询成功 taskId {}", taskId);
            return response;
        } catch (ResourceAccessException e) {
            log.error("[getTaskStatus] 翻译引擎不可用 url {}", url, e);
            throw new EngineUnavailableException("翻译引擎不可用", e);
        } catch (Exception e) {
            log.error("[getTaskStatus] 查询任务状态异常 taskId {}", taskId, e);
            throw new EngineUnavailableException("查询任务状态异常", e);
        }
    }
}
