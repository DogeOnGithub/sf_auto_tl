package com.starfield.api.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.starfield.api.dto.PromptDetailResponse;
import com.starfield.api.dto.PromptDetailResponse.TaskBriefInfo;
import com.starfield.api.dto.PromptListResponse;
import com.starfield.api.dto.PromptRequest;
import com.starfield.api.entity.CustomPrompt;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.CustomPromptRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Prompt 模板管理服务，提供多 Prompt 模板的增删改查功能
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptService {

    public static final String DEFAULT_PROMPT = "请将以下游戏文本翻译为目标语言，保持原始格式和语气。";

    final CustomPromptRepository customPromptRepository;
    final TranslationTaskRepository translationTaskRepository;

    @Value("${prompt.max-length:10000}")
    int maxLength;

    /**
     * 创建 Prompt 模板
     *
     * @param request 包含名称和内容的请求
     * @return 创建后的 Prompt 列表响应
     */
    public PromptListResponse createPrompt(PromptRequest request) {
        log.info("[createPrompt] 创建 Prompt 模板 name {}", request.name());
        validatePrompt(request);

        var prompt = new CustomPrompt();
        prompt.setName(request.name());
        prompt.setContent(request.content());
        customPromptRepository.insert(prompt);
        log.info("[createPrompt] 创建成功 id {}", prompt.getId());

        return toListResponse(prompt, 0);
    }

    /**
     * 查询所有未删除的 Prompt 模板列表（含使用次数），按更新时间倒序
     *
     * @return Prompt 模板列表
     */
    public List<PromptListResponse> listPrompts() {
        log.info("[listPrompts] 查询 Prompt 模板列表");
        var wrapper = new LambdaQueryWrapper<CustomPrompt>()
                .orderByDesc(CustomPrompt::getUpdatedAt);
        var prompts = customPromptRepository.selectList(wrapper);

        return prompts.stream()
                .map(p -> toListResponse(p, getUsageCount(p.getId())))
                .toList();
    }

    /**
     * 查询 Prompt 模板详情（含使用次数和关联任务列表）
     *
     * @param id Prompt 模板 ID
     * @return Prompt 详情响应
     */
    public PromptDetailResponse getPromptDetail(Long id) {
        log.info("[getPromptDetail] 查询 Prompt 详情 id {}", id);
        var prompt = customPromptRepository.selectById(id);
        if (Objects.isNull(prompt)) {
            throw new PromptNotFoundException(id);
        }

        var usageCount = getUsageCount(id);
        var taskWrapper = new LambdaQueryWrapper<TranslationTask>()
                .eq(TranslationTask::getPromptId, id)
                .orderByDesc(TranslationTask::getCreatedAt);
        var tasks = translationTaskRepository.selectList(taskWrapper).stream()
                .map(t -> new TaskBriefInfo(
                        t.getTaskId(),
                        t.getFileName(),
                        t.getStatus().name(),
                        t.getCreatedAt()
                ))
                .toList();

        return new PromptDetailResponse(
                prompt.getId(), prompt.getName(), prompt.getContent(),
                usageCount, tasks,
                prompt.getCreatedAt(), prompt.getUpdatedAt()
        );
    }

    /**
     * 更新 Prompt 模板
     *
     * @param id      Prompt 模板 ID
     * @param request 包含名称和内容的请求
     * @return 更新后的 Prompt 列表响应
     */
    public PromptListResponse updatePrompt(Long id, PromptRequest request) {
        log.info("[updatePrompt] 更新 Prompt 模板 id {}", id);
        validatePrompt(request);

        var prompt = customPromptRepository.selectById(id);
        if (Objects.isNull(prompt)) {
            throw new PromptNotFoundException(id);
        }

        prompt.setName(request.name());
        prompt.setContent(request.content());
        customPromptRepository.updateById(prompt);
        log.info("[updatePrompt] 更新成功 id {}", id);

        return toListResponse(prompt, getUsageCount(id));
    }

    /**
     * 软删除 Prompt 模板
     *
     * @param id Prompt 模板 ID
     */
    public void deletePrompt(Long id) {
        log.info("[deletePrompt] 删除 Prompt 模板 id {}", id);
        var prompt = customPromptRepository.selectById(id);
        if (Objects.isNull(prompt)) {
            throw new PromptNotFoundException(id);
        }
        customPromptRepository.deleteById(id);
        log.info("[deletePrompt] 删除成功 id {}", id);
    }

    /**
     * 根据 ID 获取 Prompt 内容（供 FileUploadService 调用）
     *
     * @param id Prompt 模板 ID
     * @return Prompt 内容
     */
    public String getPromptContent(Long id) {
        log.info("[getPromptContent] 获取 Prompt 内容 id {}", id);
        var prompt = customPromptRepository.selectById(id);
        if (Objects.isNull(prompt)) {
            throw new PromptNotFoundException(id);
        }
        return prompt.getContent();
    }

    /**
     * 校验 Prompt 请求参数
     *
     * @param request Prompt 请求
     */
    private void validatePrompt(PromptRequest request) {
        if (Objects.isNull(request.name()) || request.name().isBlank()) {
            throw new PromptValidationException("Prompt 名称不能为空");
        }
        if (Objects.isNull(request.content()) || request.content().isBlank()) {
            throw new PromptValidationException("Prompt 内容不能为空");
        }
        if (request.content().length() > maxLength) {
            throw new PromptValidationException("Prompt 内容超过最大长度限制 " + maxLength);
        }
    }

    /**
     * 查询 Prompt 模板的使用次数
     *
     * @param promptId Prompt 模板 ID
     * @return 使用次数
     */
    private int getUsageCount(Long promptId) {
        var wrapper = new LambdaQueryWrapper<TranslationTask>()
                .eq(TranslationTask::getPromptId, promptId);
        return Math.toIntExact(translationTaskRepository.selectCount(wrapper));
    }

    /**
     * 将实体转换为列表响应 DTO
     *
     * @param prompt     Prompt 实体
     * @param usageCount 使用次数
     * @return 列表响应 DTO
     */
    private PromptListResponse toListResponse(CustomPrompt prompt, int usageCount) {
        return new PromptListResponse(
                prompt.getId(), prompt.getName(), prompt.getContent(),
                usageCount,
                prompt.getCreatedAt(), prompt.getUpdatedAt()
        );
    }

    /**
     * Prompt 模板不存在异常
     */
    public static class PromptNotFoundException extends RuntimeException {
        public PromptNotFoundException(Long id) {
            super("Prompt 模板不存在 id " + id);
        }
    }

    /**
     * Prompt 校验异常
     */
    public static class PromptValidationException extends RuntimeException {
        public PromptValidationException(String message) {
            super(message);
        }
    }
}
