<script setup lang="ts">
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { UploadFilled } from '@element-plus/icons-vue'
import { uploadFile } from '@/services/fileApi'
import { getCreations } from '@/services/creationApi'
import { listPrompts } from '@/services/promptApi'
import type { FileUploadResponse, Creation, PromptItem } from '@/types'
import type { UploadRequestOptions } from 'element-plus'

const MAX_FILE_SIZE = 200 * 1024 * 1024 // 200MB

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

/** Prompt 选择 */
const promptMode = ref<'default' | 'select' | 'new'>('default')
const promptList = ref<PromptItem[]>([])
const selectedPromptId = ref<number | null>(null)
const newPromptName = ref('')
const newPromptContent = ref('')
const loadingPrompts = ref(false)

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

/** 上传前校验 */
function beforeUpload(file: File): boolean {
  if (!file.name.toLowerCase().endsWith('.esm')) {
    ElMessage.error('仅支持 .esm 格式的文件')
    return false
  }
  if (file.size > MAX_FILE_SIZE) {
    ElMessage.error('文件大小不能超过 200MB')
    return false
  }
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
  return true
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
    )
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
    <div class="upload-mode">
      <el-switch v-model="linkMode" active-text="关联 Creation" inactive-text="直接翻译" @change="handleLinkModeChange" />
    </div>

    <div v-if="linkMode" class="link-selector">
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

    <div class="prompt-section">
      <div class="prompt-label">Prompt 设置</div>
      <el-radio-group v-model="promptMode" @change="handlePromptModeChange">
        <el-radio value="default">默认 Prompt</el-radio>
        <el-radio value="select">选择已有</el-radio>
        <el-radio value="new">新建 Prompt</el-radio>
      </el-radio-group>

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

    <el-upload
      drag
      accept=".esm"
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
        <p class="upload-text">将 ESM 文件拖拽到此处，或 <em>点击选择文件</em></p>
        <p class="upload-hint">仅支持 .esm 格式，最大 200MB</p>
      </div>
    </el-upload>
  </div>
</template>

<style scoped>
.file-upload {
  max-width: 600px;
  margin: 0 auto;
}

.upload-mode {
  margin-bottom: 12px;
  text-align: center;
}

.link-selector {
  display: flex;
  justify-content: center;
  margin-bottom: 12px;
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
</style>
