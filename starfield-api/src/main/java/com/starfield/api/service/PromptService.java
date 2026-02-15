package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starfield.api.dto.PromptRequest;
import com.starfield.api.dto.PromptResponse;
import com.starfield.api.entity.CustomPrompt;
import com.starfield.api.repository.CustomPromptRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Prompt 管理服务，处理自定义 Prompt 的保存、查询和恢复默认
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    public static final String DEFAULT_PROMPT = "请将以下游戏文本翻译为目标语言，保持原始格式和语气。";

    final CustomPromptRepository customPromptRepository;

    @Value("${prompt.max-length:10000}")
    int maxLength;

    /**
     * 获取当前 Prompt，优先返回自定义 Prompt，否则返回默认
     *
     * @return Prompt 响应
     */
    public PromptResponse getCurrentPrompt() {
        log.info("[getCurrentPrompt] 查询当前 Prompt");
        var customPrompt = findActivePrompt();
        if (Objects.nonNull(customPrompt)) {
            log.info("[getCurrentPrompt] 返回自定义 Prompt id {}", customPrompt.getId());
            return new PromptResponse(customPrompt.getContent(), true);
        }
        log.info("[getCurrentPrompt] 返回默认 Prompt");
        return new PromptResponse(DEFAULT_PROMPT, false);
    }

    /**
     * 保存或更新自定义 Prompt，校验内容长度
     *
     * @param request Prompt 请求
     * @return Prompt 响应
     */
    public PromptResponse savePrompt(PromptRequest request) {
        log.info("[savePrompt] 保存自定义 Prompt");

        if (Objects.isNull(request.content()) || request.content().length() > maxLength) {
            log.warn("[savePrompt] Prompt 内容超过最大长度限制 maxLength {}", maxLength);
            throw new PromptTooLongException(maxLength);
        }

        var existing = findActivePrompt();
        if (Objects.nonNull(existing)) {
            existing.setContent(request.content());
            customPromptRepository.updateById(existing);
            log.info("[savePrompt] 更新已有 Prompt id {}", existing.getId());
            return new PromptResponse(existing.getContent(), true);
        }

        var prompt = new CustomPrompt();
        prompt.setContent(request.content());
        prompt.setIsActive(true);
        customPromptRepository.insert(prompt);
        log.info("[savePrompt] 创建新 Prompt");
        return new PromptResponse(prompt.getContent(), true);
    }

    /**
     * 恢复默认 Prompt，删除当前自定义 Prompt
     *
     * @return 默认 Prompt 响应
     */
    public PromptResponse resetPrompt() {
        log.info("[resetPrompt] 恢复默认 Prompt");
        var existing = findActivePrompt();
        if (Objects.nonNull(existing)) {
            log.info("[resetPrompt] 删除自定义 Prompt id {}", existing.getId());
            customPromptRepository.deleteById(existing.getId());
        }
        return new PromptResponse(DEFAULT_PROMPT, false);
    }

    /**
     * 查找当前启用的 Prompt
     *
     * @return 启用的 Prompt 或 null
     */
    private CustomPrompt findActivePrompt() {
        var wrapper = new LambdaQueryWrapper<CustomPrompt>()
                .eq(CustomPrompt::getIsActive, true);
        return customPromptRepository.selectOne(wrapper);
    }

    /**
     * Prompt 内容超过最大长度限制异常
     */
    public static class PromptTooLongException extends RuntimeException {
        public PromptTooLongException(int maxLength) {
            super("Prompt 内容超过最大长度限制 maxLength " + maxLength);
        }
    }
}
