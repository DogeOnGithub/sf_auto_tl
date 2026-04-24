# 本地开发启动命令

## 数据库 (PostgreSQL)

需要先启动 Colima（Docker runtime）：

```bash
colima start
```

然后启动 PostgreSQL 容器（挂载已有数据卷）：

```bash
docker run -d --name starfield-postgres \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=starfield \
  -e TZ=Asia/Shanghai \
  -e PGTZ=Asia/Shanghai \
  -p 5432:5432 \
  -v <your-volume-id>:/var/lib/postgresql/data \
  postgres:16-alpine
```

- 端口 5432，数据库 `starfield`，用户名/密码 `postgres/postgres`
- 数据卷 ID 替换为你本地的持久化卷，不要删除
- 如果容器已存在，用 `docker start starfield-postgres` 即可

## 前端 (starfield-web)

```bash
cd starfield-web
npm run dev
```

访问地址：http://localhost:5173/

## Engine (starfield-engine)

```bash
cd starfield-engine
LOG_LEVEL=INFO LLM_API_KEY=<your-api-key> python3 -m flask --app engine.app run --host 0.0.0.0 --port 5001
```

- 端口 5001（macOS 5000 被 AirPlay 占用）
- 环境变量 `LLM_API_KEY`（不是 `DEEPSEEK_API_KEY`）
- `LOG_LEVEL=INFO` 开启详细日志（生产环境默认 WARNING）
- 默认模型 deepseek-v4-flash，可通过 `LLM_MODEL` 环境变量覆盖

## Backend (starfield-api)

通过 IntelliJ IDEA 启动，或：

```bash
cd starfield-api
mvn spring-boot:run
```

依赖本地 PostgreSQL（端口 5432，数据库 starfield）。
