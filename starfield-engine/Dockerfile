FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip pip install gunicorn && \
    pip install -r requirements.txt

COPY engine/ engine/

EXPOSE 5001

# 必须单 worker：翻译任务状态存在 Translator._tasks 这个进程内内存 dict 里，
# 多 worker 下 GET /engine/tasks/{id} 会被随机分发到不持有该任务的进程并返回 404，
# 导致 API 侧误判引擎失联并把还在运行的任务强制置为 failed。
# 并发用 --threads 而不是 -w：线程共享同一份任务状态，翻译本身是网络 IO 等待型，多进程没有收益。
# worker-class 显式写 gthread：gunicorn 在 threads > 1 时会隐式从 sync 切过来，
# 写出来免得后面有人调回 --threads 1 时静默变成单线程阻塞。
# 代价是单点：worker 被 OOM 或异常重启会丢掉全部内存任务态，此时 API 侧靠 404 快速判死。
# 并发上限即为线程数（8 个任务），要再高需要把翻译 worker 拆成独立进程 + 共享状态存储。
CMD ["gunicorn", \
     "--worker-class", "gthread", \
     "-w", "1", \
     "--threads", "8", \
     "--timeout", "120", \
     "-b", "0.0.0.0:5001", \
     "engine.app:create_app()"]
