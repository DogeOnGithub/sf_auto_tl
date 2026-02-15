<script setup lang="ts">
import { computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { downloadFile } from '@/services/taskApi'
import type { TaskResponse } from '@/types'

const props = defineProps<{
  task: TaskResponse
}>()

/** 格式化时间为 yyyy-MM-dd HH:mm:ss */
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  var d = new Date(dateStr)
  if (isNaN(d.getTime())) return dateStr
  var pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 状态标签颜色映射 */
const statusTagType = computed(() => {
  var map: Record<string, string> = {
    waiting: 'info',
    parsing: 'warning',
    translating: '',
    assembling: 'warning',
    completed: 'success',
    failed: 'danger',
  }
  return map[props.task.status] ?? 'info'
})

/** 状态中文显示 */
const statusLabel = computed(() => {
  var map: Record<string, string> = {
    waiting: '等待中',
    parsing: '解析中',
    translating: '翻译中',
    assembling: '重组中',
    completed: '已完成',
    failed: '失败',
  }
  return map[props.task.status] ?? props.task.status
})

/** 进度百分比 */
const progressPercent = computed(() => {
  var { translated, total } = props.task.progress
  if (total <= 0) return 0
  return Math.round((translated / total) * 100)
})

/** 是否显示进度条 */
const showProgress = computed(() => {
  return ['translating', 'assembling'].includes(props.task.status)
})

/** 触发文件下载 */
async function handleDownload() {
  try {
    var res = await downloadFile(props.task.taskId)
    window.open(res.downloadUrl)
  } catch {
    ElMessage.error('下载失败，请重试')
  }
}
</script>

<template>
  <el-card class="task-card" shadow="hover">
    <div class="task-header">
      <span class="task-filename">{{ task.fileName }}</span>
      <el-tag :type="statusTagType" size="small">{{ statusLabel }}</el-tag>
    </div>

    <div class="task-meta">
      <span class="task-id">任务 ID: {{ task.taskId }}</span>
      <span class="task-time">{{ formatTime(task.updatedAt) }}</span>
    </div>

    <div v-if="task.creation" class="task-creation">
      <span class="creation-name">{{ task.creation.name }}</span>
      <span v-if="task.creation.translatedName" class="creation-translated">（{{ task.creation.translatedName }}）</span>
      <el-tag size="small" type="info">v{{ task.creation.version }}</el-tag>
    </div>

    <div v-if="showProgress" class="task-progress">
      <el-progress
        :percentage="progressPercent"
        :stroke-width="8"
        :format="() => `${task.progress.translated} / ${task.progress.total}`"
      />
    </div>

    <div v-if="task.status === 'completed'" class="task-actions">
      <el-button type="primary" size="small" :icon="Download" @click="handleDownload()">
        下载翻译文件
      </el-button>
    </div>

    <div v-if="task.status === 'failed'" class="task-error">
      <el-alert type="error" :closable="false" show-icon>
        翻译失败
      </el-alert>
    </div>
  </el-card>
</template>

<style scoped>
.task-card {
  margin-bottom: 12px;
}

.task-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.task-filename {
  font-weight: 600;
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  margin-right: 8px;
}

.task-meta {
  display: flex;
  justify-content: space-between;
  color: var(--el-text-color-placeholder);
  font-size: 12px;
  margin-bottom: 8px;
}

.task-progress {
  margin-bottom: 8px;
}

.task-actions {
  display: flex;
  gap: 8px;
}

.task-error {
  margin-top: 4px;
}

.task-creation {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
  font-size: 13px;
}

.creation-name {
  font-weight: 500;
  color: var(--el-text-color-primary);
}

.creation-translated {
  color: var(--el-text-color-secondary);
}
</style>
