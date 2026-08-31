"""引擎侧配置的集中读取点。

<p>此前 os.environ.get 散落在 llm_client、glossary_extractor、translator、cache_client 四个模块里，
同一份默认值还在 .env.example、docker-compose.yml、README 里各写了一遍，改一处漏三处。
所有环境变量在这里读一次，其他模块只 import 常量。

<p>注意：默认凭证不再来自环境变量。池化之后走默认额度的凭证一律从 Java 侧的凭证池拉取，
池为空时直接拒绝任务而不是回退到某个内置 KEY——回退会让「配置漏了」表现为「悄悄花了别的钱」。
"""

from __future__ import annotations

import logging
import os

logger = logging.getLogger(__name__)


def env_int(name: str, default: int) -> int:
    """读取正整数型环境变量 非法或非正值回退默认值。

    批次与输出上限做成可配置 是为了线上换模型时不用重新构建镜像就能调参。

    Args:
        name: 环境变量名。
        default: 默认值。

    Returns:
        解析后的正整数。
    """
    raw = os.environ.get(name)
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError:
        logger.warning("[env_int] 环境变量非整数 使用默认值 name %s raw %s default %d", name, raw, default)
        return default
    if value <= 0:
        logger.warning("[env_int] 环境变量必须为正数 使用默认值 name %s raw %s default %d", name, raw, default)
        return default
    return value


def env_int_or_none(name: str) -> int | None:
    """读取可选的正整数型环境变量 未配置或非法时返回 None。

    用于「不配置就不下发」的参数 与 env_int 的区别是没有兜底默认值。

    Args:
        name: 环境变量名。

    Returns:
        解析后的正整数 未配置或非法时为 None。
    """
    raw = os.environ.get(name)
    if not raw:
        return None
    try:
        value = int(raw)
    except ValueError:
        logger.warning("[env_int_or_none] 环境变量非整数 忽略 name %s raw %s", name, raw)
        return None
    if value <= 0:
        logger.warning("[env_int_or_none] 环境变量必须为正数 忽略 name %s raw %s", name, raw)
        return None
    return value


# Backend API 地址 缓存查询与凭证池拉取都走它
API_BASE_URL = os.environ.get("API_BASE_URL", "http://localhost:8080")

# 单个批次内对同一成员的重试次数上限
MAX_RETRIES = 3

# 指数退避间隔（秒）索引对应第几次重试
RETRY_DELAYS = [1, 2, 4]

# 我们不再显式下发 max_tokens（见 llm_client._completion_kwargs），实际上限由 provider 决定，
# 而常见 OpenAI 兼容服务的默认输出上限低到 4096。批次按 4096 的八成反推，
# 保证常规批次不会撞上限；万一撞上了还有 _should_split 的拆分兜底。
OUTPUT_TOKEN_BUDGET = env_int("LLM_OUTPUT_TOKEN_BUDGET", 3200)

# 原文字符数到输出 token 数的折算系数
# 经验值：英文原文约 4 字符 1 token，EN→ZH 译文 token 数约为原文 token 的 1.3 倍，
# 再加每行 [编号] 前缀的开销，合起来 1 字符原文约消耗 0.4 个输出 token。
# 这是估算不是保证，所以只用来定批次上限，真实截断仍靠 finish_reason 判断。
OUTPUT_TOKENS_PER_SOURCE_CHAR = 0.4

# 分批上限：字符数和记录数是「双重条件」 谁先触顶就切批
# 字符数上限由输出预算反推（3200 / 0.4 = 8000）作为输出预算的安全阀；
# 线上词条平均 73 字符 p90 214 字符 因此 80 条常规情况约 5800 字符 由记录数先触顶；
# 长 DESC 密集的批次则由 8000 字符先触顶。
DEFAULT_MAX_BATCH_CHARS = env_int(
    "LLM_MAX_BATCH_CHARS", int(OUTPUT_TOKEN_BUDGET / OUTPUT_TOKENS_PER_SOURCE_CHAR)
)
DEFAULT_MAX_BATCH_RECORDS = env_int("LLM_MAX_BATCH_RECORDS", 80)

# 单次响应输出 token 上限 未配置时不下发该参数
# 硬编码下发是有害的：上限高于 provider 或模型允许值时会直接 400，
# 而 400 会让整批走完重试后返回空结果、词条静默回退原文。
# 截断检测不依赖这个参数——撞到 provider 自己的上限时 finish_reason 同样是 length。
MAX_OUTPUT_TOKENS = env_int_or_none("LLM_MAX_OUTPUT_TOKENS")

# 单次 LLM 请求超时（秒）不设置时 SDK 默认 600s 会让卡住的批次拖住整个任务
REQUEST_TIMEOUT = env_int("LLM_REQUEST_TIMEOUT", 300)

# 检测到截断时对半拆分重试的最大深度 80 条按 2^4 可降到 5 条一批
MAX_SPLIT_DEPTH = env_int("LLM_MAX_SPLIT_DEPTH", 4)

# 单个 prompt 内最多携带的词典条数 超出时按术语长度降序截断
# 长术语更容易被误译 优先保留
MAX_PROMPT_DICT_ENTRIES = env_int("LLM_MAX_PROMPT_DICT_ENTRIES", 200)

# 译文覆盖率低于此比例视为响应不完整 触发拆分重试
# 取 0.9 而非 1.0 是容忍模型偶发漏掉个别空文本 不为此付重试成本
MIN_BATCH_COVERAGE = 0.9

# 术语表返回条数上限
# 术语表最终会被合并进词典并按批过滤下发 一个 Mod 有一两百条专有名词已经足够；
# 不设上限时模型可能吐出上千条 输出必然被截断 而截断的 JSON 解析必然失败 等于白花钱
MAX_GLOSSARY_TERMS = env_int("LLM_GLOSSARY_MAX_TERMS", 150)

# 术语提取的采样字符数上限
# 原来是 200000 字符 约 5 万 input token 在 32k 上下文的模型上直接超限 且单次调用很贵
# 6 万字符约 1.5 万 input token 对绝大多数模型都安全 均匀采样下覆盖面依然够用
DEFAULT_GLOSSARY_MAX_CHARS = env_int("LLM_GLOSSARY_MAX_CHARS", 60000)

# 采样缩减的下限 低于此值不再继续缩小 直接放弃本次提取
MIN_GLOSSARY_MAX_CHARS = 5000

# 使用默认额度时允许翻译的词条上限 超过则必须自带 API 地址和 KEY
# 默认额度是所有没填自己配置的用户共用的，一个几十万词条的 mod 一次就能把余额抽干，
# 之后所有人的任务都只能拿到 402。线上出过一次：同一个 30 万词条的 mod 被反复提交 7 次，
# 且都是 confirmation 模式（skip_cache=True 不写缓存），每次都按原价重算，两小时烧完余额。
# 池化之后被抽干的是整个池而不是单个 KEY，所以这个上限照旧生效、不随成员数放大。
MAX_ENTRIES_WITHOUT_OWN_KEY = env_int("MAX_ENTRIES_WITHOUT_OWN_KEY", 100000)

# 凭证池配置的本地缓存时长（秒）
# 任务粒度拉取 一个任务动辄几分钟到几小时 60s 的陈旧窗口足够小；
# 管理员改配置后最多一分钟生效 换来的是不必为每个批次打一次内网请求
POOL_CONFIG_TTL_SECONDS = env_int("LLM_POOL_CONFIG_TTL_SECONDS", 60)

# 用量滚动窗口天数 必须与 Java 侧 llm.pool.usage-window-days 一致
# 只作为日志与自检提示 引擎实际用的窗口用量基线由 Java 在拉取成员时给出
POOL_USAGE_WINDOW_DAYS = env_int("LLM_POOL_USAGE_WINDOW_DAYS", 7)

# 累计多少次请求就把用量增量上报一次
# 只在任务结束上报的话 跑几小时的任务中途引擎重启会丢掉全部用量 成本分散度判断失真
POOL_STAT_FLUSH_REQUESTS = env_int("LLM_POOL_STAT_FLUSH_REQUESTS", 100)

# 单个批次内最多切换几次成员
# 切换会和重试相乘放大请求数 单批最坏请求数被封在 MAX_RETRIES + 该值
POOL_MAX_MEMBER_SWITCHES = env_int("LLM_POOL_MAX_MEMBER_SWITCHES", 2)

# 各类错误对应的成员冷却时长（秒）
# 限流是短暂的 恢复快；鉴权失效、余额不足、模型名不存在都要人工介入 冷却给长一些，
# 避免每个批次都去撞同一个坏成员，把「一个成员配错」放大成整池的重试开销
POOL_COOLDOWN_RATE_LIMIT = env_int("LLM_POOL_COOLDOWN_RATE_LIMIT", 60)
POOL_COOLDOWN_AUTH = env_int("LLM_POOL_COOLDOWN_AUTH", 1800)
POOL_COOLDOWN_QUOTA = env_int("LLM_POOL_COOLDOWN_QUOTA", 1800)
POOL_COOLDOWN_MODEL_NOT_FOUND = env_int("LLM_POOL_COOLDOWN_MODEL_NOT_FOUND", 1800)
POOL_COOLDOWN_TRANSIENT = env_int("LLM_POOL_COOLDOWN_TRANSIENT", 15)
