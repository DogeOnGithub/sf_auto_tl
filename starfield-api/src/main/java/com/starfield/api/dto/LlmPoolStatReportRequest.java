package com.starfield.api.dto;

import java.util.List;

/**
 * engine 上报池成员用量增量的请求 DTO
 * <p>上报的是增量而不是快照：engine 每 100 批或任务结束 flush 一次，
 * 只在任务结束上报的话，跑几小时的任务中途 engine 重启就会丢掉全部用量，分散度判断会失真。
 *
 * @param items 各成员的用量增量
 */
public record LlmPoolStatReportRequest(
        List<Item> items
) {

    /**
     * 单个成员的用量增量
     *
     * @param memberId          成员 ID
     * @param requests          新增请求数，含失败
     * @param failures          新增失败数
     * @param promptTokens      新增输入 token
     * @param completionTokens  新增输出 token
     * @param reasoningTokens   新增推理 token
     * @param lastFailureReason 本次窗口内最后一次失败原因，无失败时为 null
     */
    public record Item(
            Long memberId,
            Long requests,
            Long failures,
            Long promptTokens,
            Long completionTokens,
            Long reasoningTokens,
            String lastFailureReason
    ) {}
}
