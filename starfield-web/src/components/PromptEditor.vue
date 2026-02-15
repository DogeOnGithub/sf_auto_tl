<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getCurrentPrompt, savePrompt, resetPrompt } from '@/services/promptApi'

const content = ref('')
const originalContent = ref('')
const isCustom = ref(false)
const loading = ref(false)
const saving = ref(false)

const isDirty = computed(() => content.value !== originalContent.value)

/** 加载当前 Prompt */
async function fetchPrompt() {
  loading.value = true
  try {
    var result = await getCurrentPrompt()
    content.value = result.content
    originalContent.value = result.content
    isCustom.value = result.isCustom
  } catch (err: any) {
    var msg = err?.response?.data?.message || '获取 Prompt 失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}

/** 保存自定义 Prompt */
async function handleSave() {
  saving.value = true
  try {
    var result = await savePrompt(content.value)
    originalContent.value = result.content
    isCustom.value = result.isCustom
    ElMessage.success('Prompt 保存成功')
  } catch (err: any) {
    var msg = err?.response?.data?.message || '保存 Prompt 失败'
    ElMessage.error(msg)
  } finally {
    saving.value = false
  }
}

/** 恢复默认 Prompt */
async function handleReset() {
  saving.value = true
  try {
    var result = await resetPrompt()
    content.value = result.content
    originalContent.value = result.content
    isCustom.value = result.isCustom
    ElMessage.success('已恢复默认 Prompt')
  } catch (err: any) {
    var msg = err?.response?.data?.message || '恢复默认 Prompt 失败'
    ElMessage.error(msg)
  } finally {
    saving.value = false
  }
}

onMounted(fetchPrompt)
</script>

<template>
  <el-card header="翻译 Prompt 设置" v-loading="loading">
    <div class="prompt-status">
      <el-tag :type="isCustom ? 'warning' : 'info'" size="small">
        {{ isCustom ? '自定义' : '默认' }}
      </el-tag>
    </div>
    <el-input
      v-model="content"
      type="textarea"
      :rows="6"
      placeholder="请输入翻译 Prompt"
      :disabled="saving"
    />
    <div class="prompt-actions">
      <el-button type="primary" :loading="saving" :disabled="!isDirty" @click="handleSave">
        保存
      </el-button>
      <el-button :loading="saving" :disabled="!isCustom" @click="handleReset">
        恢复默认
      </el-button>
    </div>
  </el-card>
</template>

<style scoped>
.prompt-status {
  margin-bottom: 12px;
}

.prompt-actions {
  margin-top: 12px;
  display: flex;
  gap: 8px;
}
</style>
