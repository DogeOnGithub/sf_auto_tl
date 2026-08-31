package com.starfield.api.dto;

/**
 * 池成员连通性测试结果 DTO
 * <p>测试由 engine 执行而非 Java 直连 LLM：只有走 engine 才能覆盖 base_url 规整、
 * 超时与可选参数这套真实调用路径。线上出过 base_url 误填完整端点导致所有调用 404、
 * 却因失败批次静默回退原文而显示「翻译完成」的事故，这个按钮就是为了提前暴露那类配置错误。
 *
 * @param success   是否调用成功
 * @param message   成功时为模型返回摘要，失败时为归一化后的错误说明
 * @param latencyMs 往返耗时（毫秒）
 */
public record LlmPoolTestResponse(
        boolean success,
        String message,
        long latencyMs
) {}
