# Starfield Mod Translator

星空（Starfield）Mod 自动翻译工具，支持 ESM 文件解析、LLM 驱动翻译、Mod 作品管理。

## 项目结构

```
starfield-engine/   Python 翻译引擎（Flask + DeepSeek LLM）
starfield-api/      Java 后端（Spring Boot + MyBatis-Plus + PostgreSQL）
starfield-web/      Vue 3 前端（Element Plus + TypeScript）
docker-compose.yml  一键部署
```

### 翻译引擎核心模块

```
starfield-engine/engine/
  esm_parser.py     ESM 二进制文件解析，提取可翻译子记录
  llm_client.py     LLM 批量翻译（标签遮蔽、多行解析、重试）
  translator.py     翻译调度器（缓存查询、去重、分批翻译、进度上报）
  cache_client.py   翻译缓存 HTTP 客户端
  esm_writer.py     翻译结果回写 ESM 文件
  prompt_builder.py Prompt 模板构建

starfield-engine/tools/
  scan_subrecords.py  ESM 子记录扫描工具（发现新的可翻译类型）
```

## 功能特性

- ESM 文件上传与自动翻译（支持自定义 Prompt 和术语词典）
- 翻译任务管理（实时进度、历史记录、文件下载）
- 翻译缓存（自动匹配已有译文，避免重复翻译，支持按类型/子类型/任务ID查询）
- ESM 解析器两层匹配（通用子记录类型 + 记录类型组合匹配，附带扫描工具发现新类型）
- 翻译引擎优化（每批次实时缓存、相同文本去重翻译、多行译文解析）
- Mod 作品管理（多版本、图片粘贴上传、预设标签、CC/Nexus 链接）
- 翻译任务与作品版本关联
- 汉化补丁上传与下载、Mod 文件替换
- 腾讯云 COS 对象存储（文件、图片、补丁统一存储）
- 翻译引擎同步失败自动标记

## 快速开始

### Docker 部署（推荐）

```bash
# 复制环境变量
cp .env.example .env
# 编辑 .env 填入你的 LLM API Key 和 COS 配置

# 启动所有服务
docker compose up -d
```

服务地址：
- 前端：http://localhost
- API：http://localhost:8080
- 引擎：http://localhost:5001

### 本地开发

#### 前置条件
- Java 17+
- Maven 3.8+
- Python 3.12+（推荐 uv 管理）
- Node.js 18+
- PostgreSQL 16

#### 数据库

```bash
# 使用 docker compose 启动 PostgreSQL，或手动创建数据库
docker compose up -d postgres
```

#### 翻译引擎

```bash
cd starfield-engine
uv venv
uv pip install -e ".[dev]"
.venv/bin/python -m engine.app
# 默认端口 5001
```

#### Java 后端

```bash
cd starfield-api
mvn spring-boot:run
# 默认端口 8080，自动执行 Flyway 数据库迁移
```

#### Vue 前端

```bash
cd starfield-web
npm install
npm run dev
# 默认端口 5173
```

## 环境变量

默认额度的 LLM 凭证由「模型池」提供，在前端星裔（管理员）页面维护，不通过环境变量配置。详见下方[模型池](#模型池)。

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `LLM_API_KEY` | 仅用于池化上线时的一次性种子导入（池为空时自动导入成第一个成员），导入后可删除 | - |
| `LLM_BASE_URL` | 同上。只填到 `/v1` 这一层，不要带 `/chat/completions` | - |
| `LLM_MODEL` | 同上 | - |
| `LLM_POOL_USAGE_WINDOW_DAYS` | 用量滚动窗口天数，调度与统计展示共用，引擎和后端必须配一致 | `7` |
| `LLM_POOL_CONFIG_TTL_SECONDS` | 引擎缓存池配置的时长（秒），管理页改完最多这么久生效 | `60` |
| `LLM_POOL_STAT_FLUSH_REQUESTS` | 累计多少次请求上报一次用量增量 | `100` |
| `LLM_POOL_MAX_MEMBER_SWITCHES` | 单批次内最多切换几次成员，与重试次数一起封住请求放大 | `2` |
| `LLM_POOL_COOLDOWN_RATE_LIMIT` | 限流（429）后的成员冷却秒数 | `60` |
| `LLM_POOL_COOLDOWN_AUTH` | 鉴权失效（401/403）后的成员冷却秒数 | `1800` |
| `LLM_POOL_COOLDOWN_QUOTA` | 余额不足（402 / quota 类 429）后的成员冷却秒数 | `1800` |
| `LLM_POOL_COOLDOWN_MODEL_NOT_FOUND` | 模型不存在（404）后的成员冷却秒数 | `1800` |
| `LLM_POOL_COOLDOWN_TRANSIENT` | 网络或 5xx 后的成员冷却秒数 | `15` |
| `MAX_ENTRIES_WITHOUT_OWN_KEY` | 走默认额度时允许翻译的词条上限，超过要求自带地址、KEY 和模型名 | `100000` |
| `LLM_OUTPUT_TOKEN_BUDGET` | 单批输出 token 预算，批次字符上限按它反推 | `3200` |
| `LLM_MAX_BATCH_CHARS` | 每批原文字符数上限 | `8000` |
| `LLM_MAX_BATCH_RECORDS` | 每批词条数上限 | `80` |
| `LLM_MAX_OUTPUT_TOKENS` | 单次响应输出 token 上限。留空即不下发该参数，由 provider 用自己的默认值；填超模型允许值会直接 400 | 不下发 |
| `LLM_REQUEST_TIMEOUT` | 单次 LLM 请求超时（秒） | `300` |
| `LLM_MAX_SPLIT_DEPTH` | 响应被截断时对半拆分重试的最大深度 | `4` |
| `LLM_MAX_PROMPT_DICT_ENTRIES` | 单个 prompt 携带的词典条数上限（已按批过滤，这是兜底） | `200` |
| `LLM_GLOSSARY_MAX_CHARS` | 术语提取的采样字符数上限 | `60000` |
| `LLM_GLOSSARY_MAX_TERMS` | 术语表返回条数上限 | `150` |
| `LOG_LEVEL` | 引擎日志级别，`INFO` 才能看到 token 用量汇总 | `WARNING`（compose 里设为 `INFO`） |
| `ENGINE_SYNC_INTERVAL_MS` | API 轮询引擎进度的间隔（毫秒） | `30000` |
| `ENGINE_SYNC_FAIL_TOLERANCE_MINUTES` | 连续同步失败多久判定任务失败（分钟） | `30` |
| `COS_SECRET_ID` | 腾讯云 COS SecretId | - |
| `COS_SECRET_KEY` | 腾讯云 COS SecretKey | - |
| `COS_REGION` | COS 存储桶地域 | `ap-guangzhou` |
| `COS_BUCKET_NAME` | COS 存储桶名称 | - |
| `COS_BASE_URL` | COS 公有读访问地址 | - |

## 模型池

未自带 API Key 的翻译任务走「模型池」提供的默认额度。池由多个成员组成，每个成员是一套
`API 地址 + Key + 模型名`，在前端星裔（管理员）页面的「模型池」里增删改查。

**调度目标是分散成本，不是提速。** 任务内的批次本来就是串行的，多成员不会让单个任务更快。
引擎按「滚动窗口内已消耗 token ÷ 配比」最小优先挑成员，并且**按批次**而不是按任务挑——
一个几十万词条的 mod 如果全压在同一个成员上，等于没有分散。

`配比（weight）` 是各成员该承担的比例，值越大承担越多。用「token ÷ 配比」而不是请求数轮询，
是因为批次大小差异很大（词条 p50 73 字符、p90 214 字符），请求数均分不等于花钱均分。

**成员故障会被自动绕开。** 调用失败按错误类型分流：

| 错误 | 处置 | 默认冷却 |
|------|------|---------|
| 限流 429 | 换成员，不等待 | 60s |
| 鉴权失效 401 / 403 | 换成员 | 30min |
| 余额不足 402 或 quota 类 429 | 换成员 | 30min |
| 模型不存在 404 | 换成员 | 30min |
| 网络异常 / 5xx | 先在同一成员上退避重试，耗尽再换 | 15s |
| 请求被拒 400 | 换一次成员确认是否为成员配置问题，仍失败则放弃本批 | 不冷却 |

单批总请求数封在 `重试次数 + 最大切换次数`，避免切换和重试相乘把成本放大。
全部成员都在冷却时不会放弃，而是挑剩余冷却最短的继续试——冷却是暂时状态，
直接判失败要用户重传整个 mod。

**用量统计按天分桶**，管理页能看到每个成员的窗口用量、成本占比、失败率和最近失败原因。
成员的实时冷却状态存在引擎内存里（引擎重启后重新探活），累计用量落库。

**「验证」按钮**会让引擎用该成员的凭证打一次极小的补全请求，走的是和真实翻译完全相同的
路径，因此能提前暴露 `base_url` 误填成完整端点、模型名不存在这类配置错误。

**池为空或全部成员停用时，走默认额度的上传会被直接拒绝**，提示用户改用自己的 KEY。
不回退到任何内置凭证：回退会让「配置漏了」表现成「悄悄花了别的钱」。

用户自带 API Key 的任务完全不经过池，也不计入池的统计。

## 技术栈

- 翻译引擎：Python 3.12 / Flask / OpenAI SDK
- 后端：Java 17 / Spring Boot 3 / MyBatis-Plus / PostgreSQL / Flyway
- 前端：Vue 3 / TypeScript / Element Plus / Vite
- 存储：腾讯云 COS 对象存储

## License

MIT
