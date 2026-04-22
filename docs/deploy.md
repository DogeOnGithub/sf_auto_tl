# 部署流程

当需要部署时，按以下步骤执行。GitHub 从服务器不可访问，所以用 tarball 方式传输。

## 服务器信息

- IP: 见 `.env` 或团队内部文档
- User: `ubuntu`
- 项目目录: `~/sf_auto_tl`

## 步骤

### 1. 本地打包

排除不需要的目录，打成 tarball：

```bash
tar czf /tmp/sf_auto_tl.tar.gz \
  --exclude='node_modules' \
  --exclude='.venv' \
  --exclude='target' \
  --exclude='dist' \
  --exclude='.git' \
  --exclude='.idea' \
  --exclude='__pycache__' \
  --exclude='.pytest_cache' \
  --exclude='.DS_Store' \
  --exclude='.env' \
  --exclude='tsconfig.tsbuildinfo' \
  --exclude='.jqwik-database' \
  -C "$(pwd)" .
```

### 2. 传输到服务器

```bash
scp /tmp/sf_auto_tl.tar.gz ubuntu@<server-ip>:/tmp/
```

### 3. 服务器解压

```bash
ssh ubuntu@<server-ip> "mkdir -p ~/sf_auto_tl && cd ~/sf_auto_tl && tar xzf /tmp/sf_auto_tl.tar.gz && rm /tmp/sf_auto_tl.tar.gz"
```

### 4. 构建并启动服务

```bash
ssh ubuntu@<server-ip> "cd ~/sf_auto_tl && docker compose up -d --build"
```

注意：这一步耗时较长（Maven 下载依赖、npm install 等）。

### 5. 验证服务状态

```bash
ssh ubuntu@<server-ip> "cd ~/sf_auto_tl && docker compose ps"
```

确认 4 个服务都是 Up 状态：postgres、starfield-engine、starfield-api、starfield-web。

### 6. 清理本地临时文件

```bash
rm -f /tmp/sf_auto_tl.tar.gz
```

## 注意事项

- 服务器 `.env` 文件已配置好生产凭证（LLM + COS），不要覆盖
- 如果 docker build 报 snapshot 错误，先执行 `docker builder prune -af`
- 访问地址: `http://<server-ip>`
