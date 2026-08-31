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
LOG_LEVEL=INFO python3 -m flask --app engine.app run --host 0.0.0.0 --port 5001
```

- 端口 5001（macOS 5000 被 AirPlay 占用）
- `LOG_LEVEL=INFO` 开启详细日志（生产环境默认 WARNING）
- 引擎不再读 `LLM_API_KEY` / `LLM_MODEL`。默认额度的凭证运行时从 backend 拉取「模型池」，
  所以本地要跑通默认额度的翻译，需要 backend 起着、且池里至少有一个启用成员
  （在前端星裔页面的「模型池」里添加，或给 backend 配一次 `LLM_API_KEY` 让它种子导入）
- 只想验证解析、重组这些不花钱的链路时，上传时打开「用我的 KEY」自带凭证即可，不依赖池

## Backend (starfield-api)

通过 IntelliJ IDEA 启动，或：

```bash
cd starfield-api
mvn spring-boot:run
```

依赖本地 PostgreSQL（端口 5432，数据库 starfield）。
