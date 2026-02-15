package com.starfield.api.entity;

/**
 * 翻译任务状态枚举
 */
public enum TaskStatus {
    waiting,
    parsing,
    translating,
    assembling,
    completed,
    failed
}
