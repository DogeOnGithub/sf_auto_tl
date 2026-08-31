<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled, Check } from '@element-plus/icons-vue'
import { uploadFile, uploadStringsZip } from '@/services/fileApi'
import { getCreations } from '@/services/creationApi'
import { listPrompts } from '@/services/promptApi'
import { createZip } from '@/utils/zip'
import type { ZipEntry } from '@/utils/zip'
import type { FileUploadResponse, Creation, PromptItem } from '@/types'
import type { UploadRequestOptions } from 'element-plus'

/** 翻译源模式由父组件控制（页面标题旁的选择器）：esm（ESM/ESP 文件）或 strings（本地化 mod 的 Strings 文件夹） */
const props = defineProps<{ sourceMode?: 'esm' | 'strings' }>()
const sourceMode = computed(() => props.sourceMode ?? 'esm')

/** Strings 文件夹选择的隐藏 input 引用 */
const stringsInput = ref<HTMLInputElement | null>(null)

/** Strings 文件三种扩展名 */
const STRINGS_EXTENSIONS = ['.strings', '.dlstrings', '.ilstrings']

const MAX_FILE_SIZE = 4096 * 1024 * 1024 // 4096MB

const emit = defineEmits<{
  'upload-success': [payload: FileUploadResponse]
}>()

const uploading = ref(false)
const uploadPercent = ref(0)

/** 关联模式 */
const linkMode = ref(false)
const creationList = ref<Creation[]>([])
const selectedCreationId = ref<number | null>(null)
const selectedVersionId = ref<number | null>(null)
const loadingCreations = ref(false)

/** 翻译模式 */
const confirmationMode = ref<'direct' | 'confirmation'>('confirmation')

/** Prompt 选择 */
const promptMode = ref<'default' | 'select' | 'new'>('default')
const promptList = ref<PromptItem[]>([])
const selectedPromptId = ref<number | null>(null)
const newPromptName = ref('')
const newPromptContent = ref('')
const loadingPrompts = ref(false)

/** 自定义 LLM 配置 */
const LLM_STORAGE_KEY = 'starfield-custom-llm'
const useCustomLlm = ref(false)
const llmBaseUrl = ref('')
const llmApiKey = ref('')
const llmModel = ref('')

/** 切换「用我的 KEY」时从 localStorage 加载保存的值 */
function handleCustomLlmChange(val: boolean) {
  if (val) {
    try {
      var saved = localStorage.getItem(LLM_STORAGE_KEY)
      if (saved) {
        var parsed = JSON.parse(saved)
        llmBaseUrl.value = parsed.baseUrl || ''
        llmApiKey.value = parsed.apiKey || ''
        llmModel.value = parsed.model || ''
      }
    } catch { /* ignore */ }
  }
}

/** 保存自定义 LLM 配置到 localStorage */
function saveLlmConfig() {
  localStorage.setItem(LLM_STORAGE_KEY, JSON.stringify({
    baseUrl: llmBaseUrl.value,
    apiKey: llmApiKey.value,
    model: llmModel.value,
  }))
}

/** 当前选中 creation 的版本列表 */
const versionOptions = computed(() => {
  if (!selectedCreationId.value) return []
  var creation = creationList.value.find(c => c.id === selectedCreationId.value)
  return creation?.versions || []
})

/** 切换关联模式时加载 creation 列表 */
async function handleLinkModeChange(val: boolean) {
  if (val && creationList.value.length === 0) {
    loadingCreations.value = true
    try {
      var res = await getCreations(1, 200)
      creationList.value = res.records
    } catch {
      ElMessage.error('加载作品列表失败')
    } finally {
      loadingCreations.value = false
    }
  }
  if (!val) {
    selectedCreationId.value = null
    selectedVersionId.value = null
  }
}

/** creation 选择变化时重置版本 */
function handleCreationChange() {
  selectedVersionId.value = null
}

/** 切换 Prompt 模式时加载 Prompt 列表 */
async function handlePromptModeChange(val: string) {
  if (val === 'select' && promptList.value.length === 0) {
    loadingPrompts.value = true
    try {
      promptList.value = await listPrompts()
    } catch {
      ElMessage.error('加载 Prompt 列表失败')
    } finally {
      loadingPrompts.value = false
    }
  }
  if (val !== 'select') {
    selectedPromptId.value = null
  }
  if (val !== 'new') {
    newPromptName.value = ''
    newPromptContent.value = ''
  }
}

/** 校验关联/Prompt/LLM 等通用选项，ESM 与 Strings 两种模式共用 */
function validateOptions(): boolean {
  if (linkMode.value && !selectedVersionId.value) {
    ElMessage.warning('请先选择要关联的作品版本')
    return false
  }
  if (promptMode.value === 'select' && !selectedPromptId.value) {
    ElMessage.warning('请选择一个 Prompt 模板')
    return false
  }
  if (promptMode.value === 'new') {
    if (!newPromptName.value.trim()) {
      ElMessage.warning('请输入新 Prompt 名称')
      return false
    }
    if (!newPromptContent.value.trim()) {
      ElMessage.warning('请输入新 Prompt 内容')
      return false
    }
  }
  if (useCustomLlm.value) {
    if (!llmBaseUrl.value.trim()) {
      ElMessage.warning('请输入 API URL')
      return false
    }
    if (!llmApiKey.value.trim()) {
      ElMessage.warning('请输入 API Key')
      return false
    }
    if (!llmModel.value.trim()) {
      ElMessage.warning('请输入模型名称')
      return false
    }
  }
  return true
}

/** 上传前校验（ESM/ESP 模式） */
function beforeUpload(file: File): boolean {
  var lowerName = file.name.toLowerCase()
  if (!lowerName.endsWith('.esm') && !lowerName.endsWith('.esp')) {
    ElMessage.error('仅支持 .esm / .esp 格式的文件')
    return false
  }
  if (file.size > MAX_FILE_SIZE) {
    ElMessage.error('文件大小不能超过 4GB')
    return false
  }
  return validateOptions()
}

/** 取文件扩展名（小写，含点） */
function getExt(name: string): string {
  var i = name.lastIndexOf('.')
  return i < 0 ? '' : name.slice(i).toLowerCase()
}

/** 触发 Strings 文件夹选择 */
function triggerStringsSelect() {
  if (uploading.value) return
  stringsInput.value?.click()
}

/** 选择 Strings 文件夹后的处理：筛选三个文件 → 校验 → 打包 zip → 上传 */
async function handleStringsFolderSelect(event: Event) {
  var input = event.target as HTMLInputElement
  var files = Array.from(input.files ?? [])
  // 清空 value，便于再次选择同一文件夹时也能触发 change
  input.value = ''
  await processStringsFiles(files)
}

/** 校验并上传 Strings 文件 */
async function processStringsFiles(files: File[]) {
  if (!validateOptions()) return

  // 按扩展名筛选三个 Strings 文件
  var picked: Record<string, File> = {}
  for (var f of files) {
    var ext = getExt(f.name)
    if (!STRINGS_EXTENSIONS.includes(ext)) continue
    if (picked[ext]) {
      ElMessage.error(`文件夹中存在多个 ${ext} 文件，请确保只有一个`)
      return
    }
    picked[ext] = f
  }

  var missing = STRINGS_EXTENSIONS.filter((e) => !picked[e])
  if (missing.length > 0) {
    ElMessage.error(`缺少 Strings 文件：${missing.join('、')}，需包含 .strings/.dlstrings/.ilstrings 三个文件`)
    return
  }

  // 校验三个文件同名且以 _zhhans 结尾
  var baseName = ''
  for (var ext of STRINGS_EXTENSIONS) {
    var name = picked[ext].name
    var base = name.slice(0, name.length - ext.length)
    if (!baseName) {
      baseName = base
    } else if (baseName.toLowerCase() !== base.toLowerCase()) {
      ElMessage.error('三个 Strings 文件名称不一致，请确认来自同一 mod')
      return
    }
  }
  if (!baseName.toLowerCase().endsWith('_zhhans')) {
    ElMessage.error('Strings 文件名必须以 _zhhans 结尾（仅支持简体中文本地化）')
    return
  }

  // 读取内容并打包为 zip（保留原始文件名）
  var entries: ZipEntry[] = []
  for (var ext of STRINGS_EXTENSIONS) {
    var file = picked[ext]
    var buf = await file.arrayBuffer()
    entries.push({ name: file.name, data: new Uint8Array(buf) })
  }
  var zipBlob = createZip(entries)
  var zipFile = new File([zipBlob], `${baseName}.zip`, { type: 'application/zip' })

  await handleStringsUpload(zipFile)
}

/** 上传打包好的 Strings zip */
async function handleStringsUpload(zipFile: File) {
  uploading.value = true
  uploadPercent.value = 0
  try {
    var versionId = linkMode.value ? (selectedVersionId.value ?? undefined) : undefined
    var pId = promptMode.value === 'select' ? (selectedPromptId.value ?? undefined) : undefined
    var pName = promptMode.value === 'new' ? newPromptName.value.trim() : undefined
    var pContent = promptMode.value === 'new' ? newPromptContent.value.trim() : undefined

    var result = await uploadStringsZip(
      zipFile,
      (percent) => { uploadPercent.value = percent },
      versionId,
      pId,
      pName || undefined,
      pContent || undefined,
      confirmationMode.value,
      useCustomLlm.value ? llmBaseUrl.value.trim() : undefined,
      useCustomLlm.value ? llmApiKey.value.trim() : undefined,
      useCustomLlm.value ? llmModel.value.trim() : undefined,
    )
    if (useCustomLlm.value) {
      saveLlmConfig()
    }
    ElMessage.success(`${result.fileName} 上传成功`)
    emit('upload-success', result)
  } catch (err: any) {
    var msg = err?.response?.data?.message || 'Strings 上传失败，请重试'
    ElMessage.error(msg)
  } finally {
    uploading.value = false
    uploadPercent.value = 0
  }
}

/** 自定义上传处理 */
async function handleUpload(options: UploadRequestOptions) {
  uploading.value = true
  uploadPercent.value = 0
  try {
    var versionId = linkMode.value ? (selectedVersionId.value ?? undefined) : undefined
    var pId = promptMode.value === 'select' ? (selectedPromptId.value ?? undefined) : undefined
    var pName = promptMode.value === 'new' ? newPromptName.value.trim() : undefined
    var pContent = promptMode.value === 'new' ? newPromptContent.value.trim() : undefined

    var result = await uploadFile(
      options.file,
      (percent) => { uploadPercent.value = percent },
      versionId,
      pId,
      pName || undefined,
      pContent || undefined,
      confirmationMode.value,
      useCustomLlm.value ? llmBaseUrl.value.trim() : undefined,
      useCustomLlm.value ? llmApiKey.value.trim() : undefined,
      useCustomLlm.value ? llmModel.value.trim() : undefined,
    )
    if (useCustomLlm.value) {
      saveLlmConfig()
    }
    ElMessage.success(`文件 ${result.fileName} 上传成功`)
    emit('upload-success', result)
  } catch (err: any) {
    var msg = err?.response?.data?.message || '文件上传失败，请重试'
    ElMessage.error(msg)
  } finally {
    uploading.value = false
    uploadPercent.value = 0
  }
}
</script>

<template>
  <div class="file-upload">
    <!-- 关联 Creation 开关 -->
    <div class="option-row">
      <el-switch v-model="linkMode" active-text="关联 Creation" inactive-text="直接翻译" @change="handleLinkModeChange" />
    </div>

    <div v-if="linkMode" class="option-row">
      <el-select
        v-model="selectedCreationId"
        placeholder="选择作品"
        filterable
        :loading="loadingCreations"
        style="width: 260px"
        @change="handleCreationChange"
      >
        <el-option
          v-for="c in creationList"
          :key="c.id"
          :label="c.translatedName ? `${c.name}（${c.translatedName}）` : c.name"
          :value="c.id"
        />
      </el-select>
      <el-select
        v-model="selectedVersionId"
        placeholder="选择版本"
        :disabled="!selectedCreationId"
        style="width: 160px; margin-left: 8px"
      >
        <el-option
          v-for="v in versionOptions"
          :key="v.id"
          :label="`v${v.version}`"
          :value="v.id"
        />
      </el-select>
    </div>

    <!-- 翻译模式 -->
    <div class="option-row">
      <span class="option-label">翻译模式</span>
      <div class="check-tags">
        <el-tooltip content="翻译完成后先人工确认译文，确认后再生成文件" placement="top">
          <div
            class="check-tag"
            :class="{ active: confirmationMode === 'confirmation' }"
            @click="confirmationMode = 'confirmation'"
          >
            <el-icon v-if="confirmationMode === 'confirmation'" class="check-icon"><Check /></el-icon>
            <span>需要人工确认</span>
          </div>
        </el-tooltip>
        <el-tooltip content="翻译完成后直接生成文件，无需人工确认" placement="top">
          <div
            class="check-tag"
            :class="{ active: confirmationMode === 'direct' }"
            @click="confirmationMode = 'direct'"
          >
            <el-icon v-if="confirmationMode === 'direct'" class="check-icon"><Check /></el-icon>
            <span>全自动</span>
          </div>
        </el-tooltip>
      </div>
    </div>

    <!-- Prompt 设置 -->
    <div class="prompt-section">
      <div class="prompt-label">Prompt 设置</div>
      <div class="check-tags">
        <div
          class="check-tag"
          :class="{ active: promptMode === 'default' }"
          @click="handlePromptModeChange('default'); promptMode = 'default'"
        >
          <el-icon v-if="promptMode === 'default'" class="check-icon"><Check /></el-icon>
          <span>默认 Prompt</span>
        </div>
        <div
          class="check-tag"
          :class="{ active: promptMode === 'select' }"
          @click="handlePromptModeChange('select'); promptMode = 'select'"
        >
          <el-icon v-if="promptMode === 'select'" class="check-icon"><Check /></el-icon>
          <span>选择已有</span>
        </div>
        <div
          class="check-tag"
          :class="{ active: promptMode === 'new' }"
          @click="handlePromptModeChange('new'); promptMode = 'new'"
        >
          <el-icon v-if="promptMode === 'new'" class="check-icon"><Check /></el-icon>
          <span>新建 Prompt</span>
        </div>
      </div>

      <div v-if="promptMode === 'select'" class="prompt-select">
        <el-select
          v-model="selectedPromptId"
          placeholder="选择 Prompt 模板"
          filterable
          :loading="loadingPrompts"
          style="width: 100%"
        >
          <el-option
            v-for="p in promptList"
            :key="p.id"
            :label="p.name"
            :value="p.id"
          />
        </el-select>
      </div>

      <div v-if="promptMode === 'new'" class="prompt-new">
        <el-input v-model="newPromptName" placeholder="Prompt 名称" style="margin-bottom: 8px" />
        <el-input v-model="newPromptContent" type="textarea" :rows="4" placeholder="Prompt 内容" />
      </div>
    </div>

    <!-- 自定义 LLM 配置 -->
    <div class="prompt-section">
      <div class="prompt-label">LLM 配置</div>
      <div class="check-tags">
        <div
          class="check-tag"
          :class="{ active: !useCustomLlm }"
          @click="useCustomLlm = false"
        >
          <el-icon v-if="!useCustomLlm" class="check-icon"><Check /></el-icon>
          <span>系统默认</span>
        </div>
        <div
          class="check-tag"
          :class="{ active: useCustomLlm }"
          @click="useCustomLlm = true; handleCustomLlmChange(true)"
        >
          <el-icon v-if="useCustomLlm" class="check-icon"><Check /></el-icon>
          <span>用我的 KEY</span>
        </div>
      </div>

      <p v-if="!useCustomLlm" class="llm-hint">
        系统默认额度为所有人共用，上限 10 万词条。超过的文件请切到「用我的 KEY」，否则会在解析后被拒绝
      </p>

      <div v-if="useCustomLlm" class="llm-config">
        <el-input v-model="llmBaseUrl" placeholder="API URL，如 https://api.deepseek.com" style="margin-bottom: 8px" />
        <el-input v-model="llmApiKey" placeholder="API Key" type="password" show-password style="margin-bottom: 8px" />
        <el-input v-model="llmModel" placeholder="模型名称，如 deepseek-v4-flash" />
        <p class="llm-hint">支持 OpenAI 兼容格式的 API（DeepSeek、通义千问、Moonshot、GLM 等）</p>
      </div>
    </div>

    <!-- ESM/ESP 上传 -->
    <el-upload
      v-if="sourceMode === 'esm'"
      drag
      accept=".esm,.esp"
      :show-file-list="false"
      :before-upload="beforeUpload"
      :http-request="handleUpload"
      :disabled="uploading"
    >
      <div v-if="uploading" class="upload-progress">
        <el-progress :percentage="uploadPercent" :stroke-width="10" />
        <p class="upload-hint">正在上传...</p>
      </div>
      <div v-else class="upload-placeholder">
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <p class="upload-text">将 ESM/ESP 文件拖拽到此处，或 <em>点击选择文件</em></p>
        <p class="upload-hint">支持 .esm / .esp 格式，最大 4GB</p>
      </div>
    </el-upload>

    <!-- Strings 文件夹上传（开启本地化的 mod） -->
    <div v-else class="strings-upload" :class="{ disabled: uploading }" @click="triggerStringsSelect">
      <input
        ref="stringsInput"
        type="file"
        webkitdirectory
        multiple
        style="display: none"
        @change="handleStringsFolderSelect"
      />
      <div v-if="uploading" class="upload-progress">
        <el-progress :percentage="uploadPercent" :stroke-width="10" />
        <p class="upload-hint">正在打包上传...</p>
      </div>
      <div v-else class="upload-placeholder">
        <el-icon class="upload-icon"><UploadFilled /></el-icon>
        <p class="upload-text">点击选择包含 strings 文件的文件夹</p>
        <p class="upload-hint">需包含 .strings / .dlstrings / .ilstrings 三个文件，且以 _zhhans 结尾</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.file-upload {
  max-width: 600px;
  margin: 0 auto;
}

.option-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.option-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.check-tags {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.check-tag {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 14px;
  border-radius: 4px;
  border: 1px solid var(--el-border-color);
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-regular);
  font-size: 13px;
  cursor: pointer;
  transition: all 0.2s;
  user-select: none;
}

.check-tag:hover {
  border-color: var(--el-color-primary-light-3);
  color: var(--el-color-primary);
}

.check-tag.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
}

.check-icon {
  font-size: 14px;
}

.prompt-section {
  margin-bottom: 16px;
  padding: 12px;
  background: var(--el-fill-color-lighter);
  border-radius: 6px;
}

.prompt-label {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}

.prompt-select {
  margin-top: 10px;
}

.prompt-new {
  margin-top: 10px;
}

.llm-config {
  margin-top: 10px;
}

.llm-hint {
  margin: 8px 0 0;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.upload-placeholder {
  padding: 20px 0;
}

.upload-icon {
  font-size: 48px;
  color: var(--el-text-color-placeholder);
  margin-bottom: 8px;
}

.upload-text {
  color: var(--el-text-color-regular);
  font-size: 14px;
  margin: 0;
}

.upload-text em {
  color: var(--el-color-primary);
  font-style: normal;
}

.upload-hint {
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  margin: 8px 0 0;
}

.upload-progress {
  padding: 30px 40px;
}

.strings-upload {
  border: 1px dashed var(--el-border-color);
  border-radius: 6px;
  text-align: center;
  cursor: pointer;
  background: var(--el-fill-color-blank);
  transition: border-color 0.2s;
}

/*
 * ESM 与 Strings 两个上传框共用同一高度口径，切换翻译来源时框体不跳动。
 * el-upload 的 dragger 自带 40px 上下内边距，自定义的 strings 框没有，
 * 这里统一改成「无上下内边距 + 固定最小高度 + 纵向居中」，两边就一致了。
 */
.file-upload :deep(.el-upload-dragger),
.strings-upload {
  box-sizing: border-box;
  min-height: 200px;
  padding: 0 10px;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  justify-content: center;
}

.strings-upload:hover {
  border-color: var(--el-color-primary);
}

.strings-upload.disabled {
  cursor: not-allowed;
  opacity: 0.7;
}
</style>
