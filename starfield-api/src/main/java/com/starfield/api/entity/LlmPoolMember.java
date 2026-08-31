package com.starfield.api.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 默认 LLM 凭证池成员实体
 * <p>只描述「走默认额度」时可用的凭证。用户自带 KEY 的任务不经过池，也不落到这张表。
 */
@Getter
@Setter
@NoArgsConstructor
@TableName("llm_pool_member")
public class LlmPoolMember {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 成员名，日志与管理页的定位标识，全局唯一 */
    @TableField("name")
    private String name;

    /** OpenAI 兼容接口地址，只到 /v1 这层，端点后缀由 engine 侧规整 */
    @TableField("base_url")
    private String baseUrl;

    /** 明文 API Key，仅 engine 专用接口返回原值，其余场景一律脱敏 */
    @TableField("api_key")
    private String apiKey;

    /** 模型名称 */
    @TableField("model")
    private String model;

    /** 成本分摊配比，调度按「窗口内用量 / weight」最小优先，值越大承担越多 */
    @TableField("weight")
    private Integer weight;

    /** 是否参与调度，停用的成员 engine 拉不到 */
    @TableField("enabled")
    private Boolean enabled;

    /** 备注，例如这个 key 属于哪个账号、额度多少 */
    @TableField("remark")
    private String remark;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
