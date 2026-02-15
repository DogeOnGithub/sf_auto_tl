<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { getTask, getTasks } from '@/services/taskApi'
import type { TaskResponse } from '@/types'
import TaskCard from './TaskCard.vue'
import { Loading } from '@element-plus/icons-vue'

const props = withDefaults(defineProps<{
  limit?: number
}>(), {
  limit: 0
})

const tasks = ref<TaskResponse[]>([])
const pollingTimers = ref<Map<string, ReturnType<typeof setInterval>>>(new Map())
const loading = ref(false)

/** 终态状态集合 */
const terminalStatuses = new Set(['completed', 'failed'])

/** 根据 limit 截取显示的任务 */
const displayedTasks = computed(() => {
  if (props.limit > 0) {
    return tasks.value.slice(0, props.limit)
  }
  return tasks.value
})

/** 判断任务是否处于终态 */
function isTerminal(status: string): boolean {
  return terminalStatuses.has(status)
}

/** 停止某个任务的轮询 */
function stopPolling(taskId: string) {
  var timer = pollingTimers.value.get(taskId)
  if (timer) {
    clearInterval(timer)
    pollingTimers.value.delete(taskId)
  }
}

/** 轮询任务状态 */
function startPolling(taskId: string) {
  if (pollingTimers.value.has(taskId)) return
  var timer = setInterval(async () => {
    try {
      var updated = await getTask(taskId)
      var idx = tasks.value.findIndex((t) => t.taskId === taskId)
      if (idx !== -1) {
        tasks.value[idx] = updated
      }
      if (isTerminal(updated.status)) {
        stopPolling(taskId)
      }
    } catch {
      // 轮询失败时静默处理，下次重试
    }
  }, 3000)
  pollingTimers.value.set(taskId, timer)
}

/** 页面加载时获取历史任务列表 */
async function loadTasks() {
  loading.value = true
  try {
    var list = await getTasks()
    tasks.value = list
    // 对非终态任务启动轮询
    list.filter((t) => !isTerminal(t.status)).forEach((t) => startPolling(t.taskId))
  } catch {
    // 加载失败静默处理
  } finally {
    loading.value = false
  }
}

/** 添加新任务（供父组件调用） */
function addTask(taskId: string) {
  // 初始化一个占位任务，立即查询一次获取真实数据
  var placeholder: TaskResponse = {
    taskId,
    fileName: '',
    status: 'waiting',
    progress: { translated: 0, total: 0 },
    creation: null,
    prompt: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  }
  tasks.value.unshift(placeholder)

  // 立即查询一次
  getTask(taskId)
    .then((data) => {
      var idx = tasks.value.findIndex((t) => t.taskId === taskId)
      if (idx !== -1) {
        tasks.value[idx] = data
      }
      if (!isTerminal(data.status)) {
        startPolling(taskId)
      }
    })
    .catch(() => {
      // 首次查询失败仍启动轮询
      startPolling(taskId)
    })
}

onMounted(() => {
  loadTasks()
})

/** 组件卸载时清理所有轮询 */
onUnmounted(() => {
  pollingTimers.value.forEach((timer) => clearInterval(timer))
  pollingTimers.value.clear()
})

defineExpose({ addTask })
</script>

<template>
  <div class="task-list">
    <h3 v-if="tasks.length > 0" class="task-list-title">翻译任务</h3>
    <TaskCard v-for="task in displayedTasks" :key="task.taskId" :task="task" />
    <el-empty v-if="!loading && tasks.length === 0" description="暂无翻译任务" :image-size="80" />
    <div v-if="loading" style="text-align: center; padding: 24px">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
    </div>
  </div>
</template>

<style scoped>
.task-list {
  max-width: 600px;
  margin: 24px auto 0;
}

.task-list-title {
  margin-bottom: 12px;
  font-size: 16px;
  color: var(--el-text-color-primary);
}
</style>
