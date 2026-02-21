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


    record EngineTranslateRequest(
            String taskId,
            String filePath,
            String targetLang,
            String customPrompt,
            List<DictionaryEntryDto> dictionaryEntries,
            String callbackUrl,
            Boolean skipCache
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
            String callbackUrl
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

}
