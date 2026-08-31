-- 默认 LLM 凭证池：把单一兜底 KEY 换成多成员池，目的是把成本分散到多个账号上
-- 用户自带 KEY 的任务不走池，也不计入这里的统计（花的是用户自己的钱）

CREATE TABLE llm_pool_member (
    id BIGSERIAL PRIMARY KEY,
    -- 成员名，日志与管理页展示用，不含敏感信息
    name TEXT NOT NULL,
    base_url TEXT NOT NULL,
    -- 明文存储。对外接口一律脱敏，日志禁止打印，只有 engine 专用的 internal 接口返回原值
    api_key TEXT NOT NULL,
    model TEXT NOT NULL,
    -- 成本分摊配比。调度按「窗口内用量 / weight」最小优先，值越大承担越多
    weight INT NOT NULL DEFAULT 1,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    remark TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- 成员名唯一：engine 日志和管理页都用它定位成员，重名会让排查失去意义
CREATE UNIQUE INDEX uk_llm_pool_member_name ON llm_pool_member (name);

-- 按天分桶的用量统计
-- 分桶而不是只存累计值，是因为累计值下新加的成员会被连续打满直到追平历史用量；
-- 按天存之后调度取最近 N 天滚动窗口，新成员不会被打爆，管理页也能看每日成本分布
CREATE TABLE llm_pool_member_stat (
    id BIGSERIAL PRIMARY KEY,
    member_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    -- 请求数与失败数分开记：失败也要计数，否则一个疯狂 429 的成员在页面上看着很干净
    requests BIGINT NOT NULL DEFAULT 0,
    failures BIGINT NOT NULL DEFAULT 0,
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    reasoning_tokens BIGINT NOT NULL DEFAULT 0,
    last_success_at TIMESTAMP,
    last_failure_at TIMESTAMP,
    last_failure_reason TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    updated_at TIMESTAMP NOT NULL DEFAULT now()
);

-- engine 按 (成员, 日期) 增量上报，靠这个唯一索引做 upsert
CREATE UNIQUE INDEX uk_llm_pool_stat_member_date ON llm_pool_member_stat (member_id, stat_date);

-- 滚动窗口查询按 stat_date 范围扫，成员维度过滤在后
CREATE INDEX idx_llm_pool_stat_date ON llm_pool_member_stat (stat_date);
