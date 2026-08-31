package com.starfield.api.dto;

/**
 * engine 专用的池成员 DTO，含明文 API Key
 * <p>只由 /api/internal/llm-pool/members 返回，前端接口一律不得复用该类型。
 * 命名带 Internal 前缀是为了在 code review 时一眼看出这是会泄露明文凭证的载荷。
 *
 * @param id      成员 ID，engine 上报统计时按它回指
 * @param name    成员名，engine 日志用
 * @param baseUrl 接口地址
 * @param apiKey  明文 API Key
 * @param model   模型名称
 * @param weight  成本分摊配比，engine 调度按它归一化用量
 * @param windowTokens 滚动窗口内已消耗的 token 数，作为 engine 调度的负载基线。
 *                     不带这个值的话 engine 重启后会认为所有成员都是零用量，
 *                     从而把流量集中打到排序靠前的那个成员上，成本分散失效
 */
public record LlmPoolInternalMember(
        Long id,
        String name,
        String baseUrl,
        String apiKey,
        String model,
        Integer weight,
        Long windowTokens
) {}
