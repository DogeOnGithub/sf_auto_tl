package com.starfield.api.client;

import java.util.List;

/**
 * 翻译引擎客户端接口，封装对 Python Translation_Engine 的 HTTP 调用
 * 具体实现在 task 3.8 中完成
 */
/**
 * 翻译引擎客户端接口，封装对 Python Translation_Engine 的 HTTP 调用
 * 具体实现在 task 3.8 中完成
 */
public interface EngineClient {

    /**
     * 向翻译引擎提交翻译任务
     *
     * @param request 翻译请求
     * @return 翻译引擎响应
     */
    EngineTranslateResponse submitTranslation(EngineTranslateRequest request);

    /**
     * 查询翻译引擎中的任务状态和进度
     *
     * @param taskId 任务 ID
     * @return 引擎任务状态响应
     */
    EngineTaskStatusResponse getTaskStatus(String taskId);

    /**
     * 向翻译引擎提交组装任务（仅组装阶段，不含翻译）
     *
     * @param request 组装请求
     * @return 引擎组装响应
     */
    EngineAssemblyResponse submitAssembly(EngineAssemblyRequest request);

    /**
     * 查询默认凭证池各成员在引擎进程内的实时健康状态
     *
     * <p>冷却状态只存在于引擎内存里，不落库，所以管理页要展示「当前是否可用」必须回源问引擎。
     *
     * @return 各成员的实时健康状态
     */
    EnginePoolHealthResponse getPoolHealth();

    /**
     * 让引擎用给定凭证打一次极小的补全请求，验证配置可用
     *
     * <p>放在引擎侧而不是 Java 直连 LLM，是为了复用引擎的 base_url 规整与客户端构造，
     * 让测试走的是和真实翻译完全相同的路径。
     *
     * @param request 待验证的凭证
     * @return 验证结果
     */
    EnginePoolTestResponse testPoolMember(EnginePoolTestRequest request);


    record EngineTranslateRequest(
            String taskId,
            String filePath,
            String targetLang,
            String customPrompt,
            List<DictionaryEntryDto> dictionaryEntries,
            String callbackUrl,
            Boolean skipCache,
            String llmBaseUrl,
            String llmApiKey,
            String llmModel,
            String sourceType
    ) {}

    record DictionaryEntryDto(
            String sourceText,
            String targetText
    ) {}

    record EngineTranslateResponse(
            String taskId,
            String status
    ) {}

    record EngineTaskStatusResponse(
            String taskId,
            String status,
            EngineProgress progress,
            String outputFilePath,
            String originalBackupPath,
            String error
    ) {}

    record EngineProgress(
            int translated,
            int total
    ) {}

    record EngineAssemblyRequest(
            String taskId,
            String filePath,
            List<AssemblyItem> items,
            String callbackUrl,
            String sourceType
    ) {}

    record AssemblyItem(
            String recordId,
            String recordType,
            String sourceText,
            String targetText
    ) {}

    record EngineAssemblyResponse(
            String taskId,
            String status
    ) {}

    record EnginePoolHealthResponse(
            List<EnginePoolMemberHealth> members
    ) {}

    record EnginePoolMemberHealth(
            Long memberId,
            Boolean available,
            Long cooldownRemainingSeconds,
            String lastErrorKind,
            String lastErrorMessage
    ) {}

    record EnginePoolTestRequest(
            String baseUrl,
            String apiKey,
            String model
    ) {}

    record EnginePoolTestResponse(
            Boolean success,
            String message,
            Long latencyMs
    ) {}

}
