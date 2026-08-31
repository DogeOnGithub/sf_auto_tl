package com.starfield.api.dto;

import java.time.LocalDateTime;

/**
 * 池成员响应 DTO，管理页用
 * <p>api_key 只回脱敏值，明文只走 engine 专用的 internal 接口。
 *
 * @param id          成员 ID
 * @param name        成员名
 * @param baseUrl     接口地址
 * @param maskedApiKey 脱敏后的 Key，形如 sk-1234****abcd
 * @param model       模型名称
 * @param weight      成本分摊配比
 * @param enabled     是否参与调度
 * @param remark      备注
 * @param stat        滚动窗口内的用量统计，成员从未被调用过时为 null
 * @param runtime     engine 进程内的实时健康状态，engine 不可达时为 null
 * @param createdAt   创建时间
 * @param updatedAt   最后修改时间
 */
public record LlmPoolMemberResponse(
        Long id,
        String name,
        String baseUrl,
        String maskedApiKey,
        String model,
        Integer weight,
        Boolean enabled,
        String remark,
        LlmPoolMemberStatSummary stat,
        LlmPoolMemberRuntime runtime,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
