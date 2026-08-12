package com.starfield.api.client;

/**
 * 引擎中不存在该翻译任务
 *
 * <p>引擎的任务状态只存在单个 gunicorn worker 的进程内存里，所以 404 是一个确定性信号：
 * 引擎进程重启过，承载这个任务的翻译线程必然已经死亡，不可能再产出结果或回调。
 * 这一点和「读超时 / 连不上」有本质区别——后者引擎可能只是在解析大文件或等 LLM 响应，
 * 任务还活着。两种情况必须分开处理，所以单独一个异常类型而不是复用 EngineUnavailableException。
 */
public class EngineTaskNotFoundException extends RuntimeException {

    /** 引擎中查不到的任务 ID */
    private final String taskId;

    public EngineTaskNotFoundException(String taskId, Throwable cause) {
        super("引擎中不存在该任务 taskId " + taskId, cause);
        this.taskId = taskId;
    }

    public String getTaskId() {
        return taskId;
    }
}
