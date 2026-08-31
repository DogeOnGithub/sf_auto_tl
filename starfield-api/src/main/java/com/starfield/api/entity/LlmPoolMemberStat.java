package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 池成员的按天用量统计实体
 * <p>按天分桶而不是只存累计值：累计值下新加入的成员会被连续打满直到追平历史用量，
 * 分桶后调度取最近 N 天滚动窗口，新成员不会被打爆，也能看出每日成本分布。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("llm_pool_member_stat")
public class LlmPoolMemberStat {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("member_id")
    private Long memberId;

    /** 统计日期，与 memberId 一起构成唯一键 */
    @TableField("stat_date")
    private LocalDate statDate;

    /** 请求总数，含失败 */
    @TableField("requests")
    private Long requests;

    /** 失败请求数，不计入的话疯狂 429 的成员在页面上看着很干净 */
    @TableField("failures")
    private Long failures;

    @TableField("prompt_tokens")
    private Long promptTokens;

    @TableField("completion_tokens")
    private Long completionTokens;

    /** 推理模型的思维链 token，单独记便于判断预算是花在推理还是译文上 */
    @TableField("reasoning_tokens")
    private Long reasoningTokens;

    @TableField("last_success_at")
    private LocalDateTime lastSuccessAt;

    @TableField("last_failure_at")
    private LocalDateTime lastFailureAt;

    /** 最近一次失败原因，engine 侧已按错误类型归一，便于判断是限流还是鉴权 */
    @TableField("last_failure_reason")
    private String lastFailureReason;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
