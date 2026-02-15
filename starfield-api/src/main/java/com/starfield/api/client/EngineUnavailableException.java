package com.starfield.api.client;

/**
 * 翻译引擎不可用异常，当 Python 引擎无法连接或返回错误时抛出
 */
public class EngineUnavailableException extends RuntimeException {

    public EngineUnavailableException(String message) {
        super(message);
    }

    public EngineUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
