package com.starfield.api.dto;

/**
 * 池成员新增/修改请求 DTO
 *
 * @param name    成员名，日志与管理页的定位标识，全局唯一
 * @param baseUrl OpenAI 兼容接口地址，只填到 /v1 这层
 * @param apiKey  API Key。更新时留空表示沿用原值，避免前端拿不到明文就无法编辑其他字段
 * @param model   模型名称
 * @param weight  成本分摊配比，留空按 1 处理
 * @param enabled 是否参与调度，留空按 true 处理
 * @param remark  备注，例如这个 key 属于哪个账号、额度多少
 */
public record LlmPoolMemberRequest(
        String name,
        String baseUrl,
        String apiKey,
        String model,
        Integer weight,
        Boolean enabled,
        String remark
) {}
