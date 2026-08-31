package com.starfield.api.dto;

import java.time.LocalDate;

/**
 * 池成员单日用量 DTO，管理页展开看成本分布用
 *
 * @param statDate         统计日期
 * @param requests         当日请求数，含失败
 * @param failures         当日失败数
 * @param promptTokens     当日输入 token
 * @param completionTokens 当日输出 token
 * @param reasoningTokens  当日推理 token
 */
public record LlmPoolDailyUsage(
        LocalDate statDate,
        long requests,
        long failures,
        long promptTokens,
        long completionTokens,
        long reasoningTokens
) {}
