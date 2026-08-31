package com.starfield.api.dto;

/**
 * 池成员在 engine 进程内的实时健康状态 DTO
 * <p>冷却是瞬时状态，只存在于 engine 内存里（engine 单 worker，进程内即全量），
 * 不落库。engine 重启后冷却态清空，属预期行为：重启后重新探活比继承过期的判死更合理。
 *
 * @param available                当前是否可被调度，冷却中为 false
 * @param cooldownRemainingSeconds 冷却剩余秒数，未冷却为 0
 * @param lastErrorKind            最近一次失败的归类（rate_limit / auth / quota / model_not_found / transient / bad_request）
 * @param lastErrorMessage         最近一次失败的原始错误摘要，排查用
 */
public record LlmPoolMemberRuntime(
        boolean available,
        long cooldownRemainingSeconds,
        String lastErrorKind,
        String lastErrorMessage
) {}
