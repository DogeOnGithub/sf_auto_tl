package com.starfield.api.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
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
     *
     * <p>404 单独抛 EngineTaskNotFoundException：引擎任务态存在进程内存里 单 worker 下
     * 404 意味着引擎重启过、翻译线程已死，是确定性结论；而超时只说明引擎正忙，任务可能还活着。
     * 两者的处置完全不同（前者立即判死并回收文件，后者要保守等待），不能混在一个异常里。
     *
     * @param taskId 任务 ID
     * @return 引擎侧任务状态
     * @throws EngineTaskNotFoundException 引擎中不存在该任务
     * @throws EngineUnavailableException  引擎不可用或返回其他错误
     */
    @Override
    public EngineTaskStatusResponse getTaskStatus(String taskId) {
        var url = engineBaseUrl + "/engine/tasks/" + taskId;
        log.debug("[getTaskStatus] 查询任务状态 taskId {}", taskId);
        try {
            var response = restTemplate.getForObject(url, EngineTaskStatusResponse.class);
            log.debug("[getTaskStatus] 任务状态查询成功 taskId {}", taskId);
            return response;
        } catch (HttpClientErrorException.NotFound e) {
            log.warn("[getTaskStatus] 引擎中不存在该任务 引擎可能重启过 taskId {}", taskId);
            throw new EngineTaskNotFoundException(taskId, e);
        } catch (ResourceAccessException e) {
            log.error("[getTaskStatus] 翻译引擎不可用 url {}", url, e);
            throw new EngineUnavailableException("翻译引擎不可用", e);
        } catch (Exception e) {
            log.error("[getTaskStatus] 查询任务状态异常 taskId {}", taskId, e);
            throw new EngineUnavailableException("查询任务状态异常", e);
        }
    }

    /**
     * 向翻译引擎提交组装任务（仅组装阶段，不含翻译）
     */
    @Override
    public EngineAssemblyResponse submitAssembly(EngineAssemblyRequest request) {
        var url = engineBaseUrl + "/engine/assembly";
        log.info("[submitAssembly] 提交组装任务 taskId {}", request.taskId());
        try {
            var response = restTemplate.postForObject(url, request, EngineAssemblyResponse.class);
            log.info("[submitAssembly] 组装任务已提交 taskId {}", request.taskId());
            return response;
        } catch (ResourceAccessException e) {
            log.error("[submitAssembly] 翻译引擎不可用 url {}", url, e);
            throw new EngineUnavailableException("翻译引擎不可用", e);
        } catch (Exception e) {
            log.error("[submitAssembly] 调用翻译引擎异常 taskId {}", request.taskId(), e);
            throw new EngineUnavailableException("调用翻译引擎异常", e);
        }
    }

    /**
     * 查询默认凭证池各成员在引擎进程内的实时健康状态
     */
    @Override
    public EnginePoolHealthResponse getPoolHealth() {
        var url = engineBaseUrl + "/engine/pool";
        log.debug("[getPoolHealth] 查询池健康状态");
        try {
            return restTemplate.getForObject(url, EnginePoolHealthResponse.class);
        } catch (ResourceAccessException e) {
            log.warn("[getPoolHealth] 翻译引擎不可用 url {}", url);
            throw new EngineUnavailableException("翻译引擎不可用", e);
        } catch (Exception e) {
            log.warn("[getPoolHealth] 查询池健康状态异常 url {}", url, e);
            throw new EngineUnavailableException("查询池健康状态异常", e);
        }
    }

    /**
     * 让引擎用给定凭证打一次极小的补全请求，验证配置可用
     *
     * <p>异常不外抛为 EngineUnavailableException：调用方要区分「引擎挂了」和「这套凭证不可用」，
     * 后者是测试按钮的正常结果而不是系统故障，所以统一收敛成 success=false 的结果对象。
     * 日志里不打 request 内容，它带明文 Key。
     */
    @Override
    public EnginePoolTestResponse testPoolMember(EnginePoolTestRequest request) {
        var url = engineBaseUrl + "/engine/pool/test";
        log.info("[testPoolMember] 请求引擎验证凭证 model {}", request.model());
        try {
            return restTemplate.postForObject(url, request, EnginePoolTestResponse.class);
        } catch (ResourceAccessException e) {
            log.warn("[testPoolMember] 翻译引擎不可用 url {}", url);
            return new EnginePoolTestResponse(false, "翻译引擎不可用 无法执行验证", 0L);
        } catch (Exception e) {
            log.warn("[testPoolMember] 调用引擎验证接口异常 url {}", url, e);
            return new EnginePoolTestResponse(false, "调用引擎验证接口异常 " + e.getMessage(), 0L);
        }
    }

}
