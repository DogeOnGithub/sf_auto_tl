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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

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
    final LlmPoolService llmPoolService;

    @Value("${storage.upload-dir:./uploads}")
    private String uploadDir;

    @Value("${api.base-url:http://localhost:8080}")
    private String apiBaseUrl;

    private static final String ESM_EXTENSION = ".esm";
    private static final String ESP_EXTENSION = ".esp";
    private static final byte[] ESM_MAGIC_BYTES = "TES4".getBytes();

    // Strings 模式：三个文件扩展名（小写，顺序固定用于确定性遍历）
    private static final List<String> STRINGS_EXTENSIONS = List.of(".strings", ".dlstrings", ".ilstrings");
    private static final Set<String> STRINGS_EXT_SET = Set.copyOf(STRINGS_EXTENSIONS);
    // 仅支持简体中文本地化，文件名须以该后缀结尾
    private static final String ZHHANS_SUFFIX = "_zhhans";
    // 单个 Strings 文件大小上限（防止 zip 炸弹）
    private static final long MAX_STRINGS_FILE_SIZE = 100L * 1024 * 1024;
    // zip 内条目数量上限
    private static final int MAX_ZIP_ENTRIES = 100;
    // Strings 来源类型标识
    private static final String SOURCE_TYPE_STRINGS = "strings";

    /**
     * 处理文件上传：校验格式、存储文件、解析 Prompt、创建任务、提交翻译引擎
     *
     * @param file              上传的文件
     * @param creationVersionId 关联的 creation 版本 ID（可选）
     * @param promptId          选择已有 Prompt 的 ID（可选）
     * @param newPromptName     现场编写的 Prompt 名称（可选）
     * @param newPromptContent  现场编写的 Prompt 内容（可选）
     * @param confirmationMode  翻译确认模式（direct 或 confirmation，可选，默认 direct）
     * @param llmBaseUrl        自定义 LLM API 地址（可选）
     * @param llmApiKey         自定义 LLM API Key（可选，不持久化）
     * @param llmModel          自定义 LLM 模型名称（可选）
     * @param ignoreAlreadyTranslated 是否忽略「文件已汉化」的拦截（星裔专用，可选，默认 false）
     * @return 上传响应（taskId + fileName）
     * @throws IOException 文件存储异常
     */
    public FileUploadResponse upload(MultipartFile file, Long creationVersionId,
                                     Long promptId, String newPromptName,
                                     String newPromptContent, String confirmationMode,
                                     String llmBaseUrl, String llmApiKey, String llmModel,
                                     boolean ignoreAlreadyTranslated) throws IOException {
        var fileName = file.getOriginalFilename();
        log.info("[upload] 开始处理文件上传 fileName {} creationVersionId {} confirmationMode {} llmModel {} ignoreAlreadyTranslated {}", fileName, creationVersionId, confirmationMode, llmModel, ignoreAlreadyTranslated);

        requireDefaultQuotaAvailable(llmBaseUrl, llmApiKey, llmModel);
        validateEsmFormat(file);

        var resolvedPrompt = resolvePrompt(promptId, newPromptName, newPromptContent);

        var taskId = UUID.randomUUID().toString();
        var storedPath = storeFile(file, taskId);

        var resolvedMode = (Objects.isNull(confirmationMode) || confirmationMode.isBlank()) ? "direct" : confirmationMode;

        var task = createTask(taskId, fileName, storedPath);
        task.setCreationVersionId(creationVersionId);
        task.setPromptId(resolvedPrompt.id());
        task.setConfirmationMode(resolvedMode);
        task.setLlmBaseUrl(llmBaseUrl);
        task.setLlmModel(llmModel);
        translationTaskRepository.insert(task);
        log.info("[upload] 任务创建成功 taskId {} promptId {} confirmationMode {} llmModel {}", taskId, resolvedPrompt.id(), resolvedMode, llmModel);

        submitToEngine(task, resolvedPrompt.content(), llmBaseUrl, llmApiKey, llmModel, ignoreAlreadyTranslated);

        return new FileUploadResponse(taskId, fileName);
    }

    /**
     * 处理开启本地化 mod 的 Strings 上传（前端将 strings 文件夹打包为 zip）
     * 流程：解压校验（三文件齐全、同名、_zhhans 后缀、结构合法）→ 存储到 uploads/{taskId}/ → 创建任务 → 提交引擎
     *
     * @param file              前端打包的 zip 文件（内含三个 Strings 文件）
     * @param creationVersionId 关联的 creation 版本 ID（可选）
     * @param promptId          选择已有 Prompt 的 ID（可选）
     * @param newPromptName     现场编写的 Prompt 名称（可选）
     * @param newPromptContent  现场编写的 Prompt 内容（可选）
     * @param confirmationMode  翻译确认模式（direct 或 confirmation，可选，默认 direct）
     * @param llmBaseUrl        自定义 LLM API 地址（可选）
     * @param llmApiKey         自定义 LLM API Key（可选，不持久化）
     * @param llmModel          自定义 LLM 模型名称（可选）
     * @param ignoreAlreadyTranslated 是否忽略「文件已汉化」的拦截（星裔专用，可选，默认 false）
     * @return 上传响应（taskId + baseName）
     * @throws IOException 文件处理异常
     */
    public FileUploadResponse uploadStrings(MultipartFile file, Long creationVersionId,
                                            Long promptId, String newPromptName,
                                            String newPromptContent, String confirmationMode,
                                            String llmBaseUrl, String llmApiKey, String llmModel,
                                            boolean ignoreAlreadyTranslated) throws IOException {
        var zipName = file.getOriginalFilename();
        log.info("[uploadStrings] 开始处理 Strings 上传 zipName {} creationVersionId {} confirmationMode {} llmModel {} ignoreAlreadyTranslated {}", zipName, creationVersionId, confirmationMode, llmModel, ignoreAlreadyTranslated);

        requireDefaultQuotaAvailable(llmBaseUrl, llmApiKey, llmModel);
        var taskId = UUID.randomUUID().toString();
        var extracted = extractAndValidateStrings(file, taskId);

        var resolvedPrompt = resolvePrompt(promptId, newPromptName, newPromptContent);

        var resolvedMode = (Objects.isNull(confirmationMode) || confirmationMode.isBlank()) ? "direct" : confirmationMode;

        var task = createTask(taskId, extracted.baseName(), extracted.dir());
        task.setSourceType(SOURCE_TYPE_STRINGS);
        task.setCreationVersionId(creationVersionId);
        task.setPromptId(resolvedPrompt.id());
        task.setConfirmationMode(resolvedMode);
        task.setLlmBaseUrl(llmBaseUrl);
        task.setLlmModel(llmModel);
        translationTaskRepository.insert(task);
        log.info("[uploadStrings] 任务创建成功 taskId {} baseName {} confirmationMode {}", taskId, extracted.baseName(), resolvedMode);

        submitToEngine(task, resolvedPrompt.content(), llmBaseUrl, llmApiKey, llmModel, ignoreAlreadyTranslated);

        return new FileUploadResponse(taskId, extracted.baseName());
    }

    /**
     * 解压 zip 并校验 Strings 文件，合格后存储到 uploads/{taskId}/ 目录
     * 校验规则：恰好包含 .strings/.dlstrings/.ilstrings 三个文件（大小写不敏感）、三者同名、
     * 文件名以 _zhhans 结尾、每个文件头部结构合法（8 + count*8 + dataSize == 文件长度）
     *
     * @param file   上传的 zip 文件
     * @param taskId 任务 ID（用于生成存储目录）
     * @return 提取结果（存储目录 + 文件基础名）
     * @throws IOException 解压或写入异常
     */
    StringsExtractResult extractAndValidateStrings(MultipartFile file, String taskId) throws IOException {
        var uploadPath = Paths.get(uploadDir);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }
        var targetDir = uploadPath.resolve(taskId);
        Files.createDirectories(targetDir);

        // ext(小写) -> 内容 / 原始文件名
        var contents = new LinkedHashMap<String, byte[]>();
        var names = new LinkedHashMap<String, String>();

        try (var zis = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            var entryCount = 0;
            while ((entry = zis.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ZIP_ENTRIES) {
                    throw new InvalidStringsFormatException("压缩包内文件过多");
                }
                if (entry.isDirectory()) {
                    continue;
                }
                // 仅取文件名部分，丢弃目录层级，防止 zip-slip 路径穿越
                var simpleName = Paths.get(entry.getName()).getFileName().toString();
                var dotIdx = simpleName.lastIndexOf('.');
                if (dotIdx < 0) {
                    continue;
                }
                var ext = simpleName.substring(dotIdx).toLowerCase();
                if (!STRINGS_EXT_SET.contains(ext)) {
                    continue;
                }
                if (contents.containsKey(ext)) {
                    throw new InvalidStringsFormatException("压缩包内存在重复的 " + ext + " 文件");
                }
                contents.put(ext, readCapped(zis, MAX_STRINGS_FILE_SIZE));
                names.put(ext, simpleName);
            }
        }

        if (!contents.keySet().containsAll(STRINGS_EXT_SET)) {
            throw new InvalidStringsFormatException("必须包含 .strings、.dlstrings、.ilstrings 三个文件");
        }

        // 校验三个文件同名且以 _zhhans 结尾
        String baseName = null;
        for (var ext : STRINGS_EXTENSIONS) {
            var simpleName = names.get(ext);
            var base = simpleName.substring(0, simpleName.length() - ext.length());
            if (Objects.isNull(baseName)) {
                baseName = base;
            } else if (!baseName.equalsIgnoreCase(base)) {
                throw new InvalidStringsFormatException("三个 Strings 文件名称不一致 " + baseName + " / " + base);
            }
        }
        if (Objects.isNull(baseName) || !baseName.toLowerCase().endsWith(ZHHANS_SUFFIX)) {
            throw new InvalidStringsFormatException("Strings 文件名必须以 _zhhans 结尾（仅支持简体中文本地化）");
        }

        // 校验结构并写入目录
        for (var ext : STRINGS_EXTENSIONS) {
            var bytes = contents.get(ext);
            validateStringsHeader(bytes, ext);
            Files.write(targetDir.resolve(names.get(ext)), bytes);
        }

        log.info("[extractAndValidateStrings] Strings 文件校验并存储成功 taskId {} baseName {} dir {}", taskId, baseName, targetDir);
        return new StringsExtractResult(targetDir, baseName);
    }

    /**
     * 校验单个 Strings 文件头部结构：8 + count*8 + dataSize 应等于文件总长度
     *
     * @param bytes 文件字节
     * @param ext   扩展名（用于日志与异常信息）
     */
    void validateStringsHeader(byte[] bytes, String ext) {
        if (bytes.length < 8) {
            log.warn("[validateStringsHeader] 文件过小 ext {} length {}", ext, bytes.length);
            throw new InvalidStringsFormatException(ext + " 不是有效的 Strings 文件");
        }
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var count = buffer.getInt(0) & 0xFFFFFFFFL;
        var dataSize = buffer.getInt(4) & 0xFFFFFFFFL;
        var expected = 8L + count * 8L + dataSize;
        if (expected != bytes.length) {
            log.warn("[validateStringsHeader] 文件结构非法 ext {} count {} dataSize {} length {}", ext, count, dataSize, bytes.length);
            throw new InvalidStringsFormatException(ext + " 文件结构非法");
        }
    }

    /**
     * 从输入流读取字节，超过上限则抛出异常（防止 zip 炸弹）
     *
     * @param is       输入流
     * @param maxBytes 最大字节数
     * @return 读取到的字节
     * @throws IOException 读取异常
     */
    private byte[] readCapped(InputStream is, long maxBytes) throws IOException {
        var buffer = new ByteArrayOutputStream();
        var chunk = new byte[8192];
        var total = 0L;
        int n;
        while ((n = is.read(chunk)) != -1) {
            total += n;
            if (total > maxBytes) {
                throw new InvalidStringsFormatException("Strings 文件过大 超过 " + (maxBytes / (1024 * 1024)) + "MB");
            }
            buffer.write(chunk, 0, n);
        }
        return buffer.toByteArray();
    }

    /**
     * Strings 提取结果
     */
    record StringsExtractResult(Path dir, String baseName) {}

    /**
     * 无效 Strings 格式异常
     */
    public static class InvalidStringsFormatException extends RuntimeException {
        public InvalidStringsFormatException(String message) {
            super(message);
        }
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
     * 未自带凭证时校验默认凭证池仍有可用成员
     *
     * <p>卡在上传入口而不是等引擎判定：文件最大 4GB，等传完再由引擎在解析阶段失败，
     * 用户要白等几分钟才知道配置层面根本没额度可用。这里提前拦掉，提示直接引导去开「用我的 KEY」。
     *
     * <p>只看配置层面有没有启用成员。成员全部正在冷却是引擎侧的瞬时状态，不在这里拦，
     * 否则会把「几分钟后自然恢复」误判成「请自带 KEY」。
     *
     * <p>判空口径与引擎的 _has_own_llm_credentials 保持一致：地址、Key、模型名三项必须同时非空
     * 才算自带。缺任何一项引擎都会回落到默认凭证池，那时花的是公共额度而不是用户自己的钱；
     * 如果这里只校验其中两项，「填地址和 Key、不填模型名」就是一条绕过池校验去消耗公共额度的路径。
     *
     * @param llmBaseUrl 用户填写的 LLM API 地址
     * @param llmApiKey  用户填写的 LLM API Key
     * @param llmModel   用户填写的模型名称
     * @throws LlmPoolService.PoolUnavailableException 未自带完整凭证且池中无启用成员
     */
    private void requireDefaultQuotaAvailable(String llmBaseUrl, String llmApiKey, String llmModel) {
        var hasOwnCredentials = isPresent(llmBaseUrl) && isPresent(llmApiKey) && isPresent(llmModel);
        if (hasOwnCredentials) {
            return;
        }
        if (!llmPoolService.hasEnabledMember()) {
            log.warn("[requireDefaultQuotaAvailable] 未自带完整凭证且默认凭证池无启用成员 拒绝上传");
            throw new LlmPoolService.PoolUnavailableException();
        }
    }

    /**
     * 判断字符串非空且非空白
     *
     * <p>空白串必须等同于没提供：引擎侧的判空同样用 strip 口径，
     * 若这里只判 null，传几个空格就能骗过「自带凭证」的判定。
     *
     * @param value 待判断的值
     * @return 非空且含非空白字符返回 true
     */
    private boolean isPresent(String value) {
        return Objects.nonNull(value) && !value.isBlank();
    }

    /**
     * 向翻译引擎提交翻译任务，传递 customPrompt、dictionaryEntries 和自定义 LLM 配置
     *
     * @param task         翻译任务
     * @param customPrompt 解析后的 Prompt 内容
     * @param llmBaseUrl   自定义 LLM API 地址（可选）
     * @param llmApiKey    自定义 LLM API Key（可选）
     * @param llmModel     自定义 LLM 模型名称（可选）
     * @param ignoreAlreadyTranslated 是否忽略「文件已汉化」的拦截（星裔专用）
     */
    void submitToEngine(TranslationTask task, String customPrompt,
                        String llmBaseUrl, String llmApiKey, String llmModel,
                        boolean ignoreAlreadyTranslated) {
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
                    skipCache,
                    llmBaseUrl,
                    llmApiKey,
                    llmModel,
                    task.getSourceType(),
                    ignoreAlreadyTranslated
            );

            engineClient.submitTranslation(request);
            log.info("[submitToEngine] 翻译任务已提交到引擎 taskId {} ignoreAlreadyTranslated {}", task.getTaskId(), ignoreAlreadyTranslated);
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
