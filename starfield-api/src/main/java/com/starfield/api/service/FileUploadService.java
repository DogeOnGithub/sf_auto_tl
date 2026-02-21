package com.starfield.api.service;

import com.starfield.api.client.EngineClient;
import com.starfield.api.dto.FileUploadResponse;
import com.starfield.api.dto.PromptRequest;
import com.starfield.api.entity.TaskStatus;
import com.starfield.api.entity.TranslationTask;
import com.starfield.api.repository.DictionaryEntryRepository;
import com.starfield.api.repository.TranslationTaskRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.UUID;

/**
 * 文件上传服务，处理 ESM 文件上传、校验、存储和任务创建
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileUploadService {

    final TranslationTaskRepository translationTaskRepository;
    final PromptService promptService;
    final DictionaryEntryRepository dictionaryEntryRepository;
    final EngineClient engineClient;

    @Value("${storage.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${api.base-url:http://localhost:8080}")
    private String apiBaseUrl;

    private static final String ESM_EXTENSION = ".esm";
    private static final String ESP_EXTENSION = ".esp";
    private static final byte[] ESM_MAGIC_BYTES = "TES4".getBytes();

    /**
     * 处理文件上传：校验格式、存储文件、解析 Prompt、创建任务、提交翻译引擎
     *
     * @param file              上传的文件
     * @param creationVersionId 关联的 creation 版本 ID（可选）
     * @param promptId          选择已有 Prompt 的 ID（可选）
     * @param newPromptName     现场编写的 Prompt 名称（可选）
     * @param newPromptContent  现场编写的 Prompt 内容（可选）
     * @param confirmationMode  翻译确认模式（direct 或 confirmation，可选，默认 direct）
     * @return 上传响应（taskId + fileName）
     * @throws IOException 文件存储异常
     */
    public FileUploadResponse upload(MultipartFile file, Long creationVersionId,
                                     Long promptId, String newPromptName,
                                     String newPromptContent, String confirmationMode) throws IOException {
        var fileName = file.getOriginalFilename();
        log.info("[upload] 开始处理文件上传 fileName {} creationVersionId {} confirmationMode {}", fileName, creationVersionId, confirmationMode);

        validateEsmFormat(file);

        var resolvedPrompt = resolvePrompt(promptId, newPromptName, newPromptContent);

        var taskId = UUID.randomUUID().toString();
        var storedPath = storeFile(file, taskId);

        var resolvedMode = (Objects.isNull(confirmationMode) || confirmationMode.isBlank()) ? "direct" : confirmationMode;

        var task = createTask(taskId, fileName, storedPath);
        task.setCreationVersionId(creationVersionId);
        task.setPromptId(resolvedPrompt.id());
        task.setConfirmationMode(resolvedMode);
        translationTaskRepository.insert(task);
        log.info("[upload] 任务创建成功 taskId {} promptId {} confirmationMode {}", taskId, resolvedPrompt.id(), resolvedMode);

        submitToEngine(task, resolvedPrompt.content());

        return new FileUploadResponse(taskId, fileName);
    }

    /**
     * 解析 Prompt：现场编写优先 → 选择已有 → 默认
     *
     * @param promptId        选择已有 Prompt 的 ID（可选）
     * @param newPromptName   现场编写的 Prompt 名称（可选）
     * @param newPromptContent 现场编写的 Prompt 内容（可选）
     * @return 解析后的 Prompt（ID 和内容）
     */
    ResolvedPrompt resolvePrompt(Long promptId, String newPromptName, String newPromptContent) {
        if (Objects.nonNull(newPromptContent) && !newPromptContent.isBlank()) {
            log.info("[resolvePrompt] 现场编写 Prompt name {}", newPromptName);
            var created = promptService.createPrompt(new PromptRequest(newPromptName, newPromptContent));
            return new ResolvedPrompt(created.id(), newPromptContent);
        }

        if (Objects.nonNull(promptId)) {
            log.info("[resolvePrompt] 选择已有 Prompt promptId {}", promptId);
            var content = promptService.getPromptContent(promptId);
            return new ResolvedPrompt(promptId, content);
        }

        log.info("[resolvePrompt] 使用默认 Prompt");
        return new ResolvedPrompt(null, PromptService.DEFAULT_PROMPT);
    }

    /**
     * 解析后的 Prompt 信息
     */
    record ResolvedPrompt(Long id, String content) {}

    /**
     * 校验文件是否为有效的 ESM 格式（扩展名 + 魔数字节）
     *
     * @param file 待校验的文件
     */
    void validateEsmFormat(MultipartFile file) {
        var fileName = file.getOriginalFilename();

        var lowerName = Objects.isNull(fileName) ? "" : fileName.toLowerCase();
        if (!lowerName.endsWith(ESM_EXTENSION) && !lowerName.endsWith(ESP_EXTENSION)) {
            log.warn("[validateEsmFormat] 文件扩展名不是 .esm 或 .esp fileName {}", fileName);
            throw new InvalidEsmFormatException();
        }

        try (InputStream is = file.getInputStream()) {
            var header = new byte[4];
            var bytesRead = is.read(header);
            if (bytesRead < 4 || !java.util.Arrays.equals(header, ESM_MAGIC_BYTES)) {
                log.warn("[validateEsmFormat] 文件魔数字节不匹配 fileName {}", fileName);
                throw new InvalidEsmFormatException();
            }
        } catch (IOException e) {
            log.error("[validateEsmFormat] 读取文件头失败 fileName {}", fileName, e);
            throw new InvalidEsmFormatException();
        }
    }

    /**
     * 存储文件到上传目录
     *
     * @param file   上传的文件
     * @param taskId 任务 ID（用于生成唯一文件名）
     * @return 存储后的文件路径
     * @throws IOException 文件写入异常
     */
    Path storeFile(MultipartFile file, String taskId) throws IOException {
        var uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        var originalName = file.getOriginalFilename();
        var ext = Objects.nonNull(originalName) && originalName.toLowerCase().endsWith(ESP_EXTENSION)
                ? ESP_EXTENSION : ESM_EXTENSION;
        var storedFileName = taskId + ext;
        var targetPath = uploadPath.resolve(storedFileName);
        Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);
        log.info("[storeFile] 文件存储成功 path {}", targetPath);
        return targetPath;
    }

    /**
     * 创建翻译任务实体
     *
     * @param taskId   任务 ID
     * @param fileName 原始文件名
     * @param filePath 存储路径
     * @return 翻译任务实体
     */
    TranslationTask createTask(String taskId, String fileName, Path filePath) {
        var task = new TranslationTask();
        task.setTaskId(taskId);
        task.setFileName(fileName);
        task.setFilePath(filePath.toString());
        task.setStatus(TaskStatus.waiting);
        return task;
    }

    /**
     * 向翻译引擎提交翻译任务，传递 customPrompt 和 dictionaryEntries
     *
     * @param task         翻译任务
     * @param customPrompt 解析后的 Prompt 内容
     */
    void submitToEngine(TranslationTask task, String customPrompt) {
        try {
            var dictionaryEntries = dictionaryEntryRepository.selectList(null).stream()
                    .map(entry -> new EngineClient.DictionaryEntryDto(
                            entry.getSourceText(),
                            entry.getTargetText()
                    ))
                    .toList();

            var absoluteFilePath = Paths.get(task.getFilePath()).toAbsolutePath().toString();
            var callbackUrl = apiBaseUrl + "/api/tasks/" + task.getTaskId() + "/progress";
            var skipCache = "confirmation".equals(task.getConfirmationMode());
            var request = new EngineClient.EngineTranslateRequest(
                    task.getTaskId(),
                    absoluteFilePath,
                    task.getTargetLang(),
                    customPrompt,
                    dictionaryEntries,
                    callbackUrl,
                    skipCache
            );

            engineClient.submitTranslation(request);
            log.info("[submitToEngine] 翻译任务已提交到引擎 taskId {}", task.getTaskId());
        } catch (Exception e) {
            log.error("[submitToEngine] 提交翻译引擎失败 taskId {}", task.getTaskId(), e);
        }
    }

    /**
     * 无效 ESM 格式异常
     */
    public static class InvalidEsmFormatException extends RuntimeException {
        public InvalidEsmFormatException() {
            super("文件不是有效的 ESM/ESP 格式");
        }
    }
}
