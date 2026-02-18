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

| 变量 | 说明 | 默认值 |
|------|------|--------|
| `LLM_API_KEY` | LLM API 密钥 | - |
| `LLM_BASE_URL` | LLM API 地址 | `https://api.deepseek.com/v1` |
| `LLM_MODEL` | LLM 模型名称 | `deepseek-reasoner` |
| `COS_SECRET_ID` | 腾讯云 COS SecretId | - |
| `COS_SECRET_KEY` | 腾讯云 COS SecretKey | - |
| `COS_REGION` | COS 存储桶地域 | `ap-guangzhou` |
| `COS_BUCKET_NAME` | COS 存储桶名称 | - |
| `COS_BASE_URL` | COS 公有读访问地址 | - |

## 技术栈

- 翻译引擎：Python 3.12 / Flask / OpenAI SDK
- 后端：Java 17 / Spring Boot 3 / MyBatis-Plus / PostgreSQL / Flyway
- 前端：Vue 3 / TypeScript / Element Plus / Vite
- 存储：腾讯云 COS 对象存储

## License

MIT
