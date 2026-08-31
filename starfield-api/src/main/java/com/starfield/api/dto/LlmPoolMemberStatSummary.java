package com.starfield.api.dto;

import java.time.LocalDateTime;

/**
 * 池成员用量统计摘要 DTO
 * <p>窗口口径与调度用的口径一致（最近 N 天），这样管理页看到的用量分布就是调度实际依据的分布。
 * 累计口径额外给出，用来看历史总消耗。
 *
 * @param windowDays        统计窗口天数，与调度口径一致
 * @param windowRequests    窗口内请求数，含失败
 * @param windowFailures    窗口内失败数
 * @param windowTokens      窗口内消耗的总 token（prompt + completion + reasoning），调度排序的依据
 * @param totalRequests     累计请求数
 * @param totalFailures     累计失败数
 * @param totalTokens       累计消耗 token
 * @param lastSuccessAt     最近一次成功时间
 * @param lastFailureAt     最近一次失败时间
 * @param lastFailureReason 最近一次失败原因，已按错误类型归一
 */
public record LlmPoolMemberStatSummary(
        int windowDays,
        long windowRequests,
        long windowFailures,
        long windowTokens,
        long totalRequests,
        long totalFailures,
        long totalTokens,
        LocalDateTime lastSuccessAt,
        LocalDateTime lastFailureAt,
        String lastFailureReason
) {}
