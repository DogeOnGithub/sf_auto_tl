<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { getTask, getTasks } from '@/services/taskApi'
import type { TaskResponse } from '@/types'
import TaskCard from './TaskCard.vue'
import { Loading } from '@element-plus/icons-vue'

const tasks = ref<TaskResponse[]>([])
const pollingTimers = ref<Map<string, ReturnType<typeof setInterval>>>(new Map())
const loading = ref(false)

const terminalStatuses = new Set(['completed', 'failed'])

function isTerminal(status: string): boolean {
  return terminalStatuses.has(status)
}

function stopPolling(taskId: string) {
  var timer = pollingTimers.value.get(taskId)
  if (timer) {
    clearInterval(timer)
    pollingTimers.value.delete(taskId)
  }
}

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
      // 静默处理
    }
  }, 3000)
  pollingTimers.value.set(taskId, timer)
}

async function loadTasks() {
  loading.value = true
  try {
    var list = await getTasks()
    tasks.value = list
    list.filter((t) => !isTerminal(t.status)).forEach((t) => startPolling(t.taskId))
  } catch {
    // 静默处理
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadTasks()
})

onUnmounted(() => {
  pollingTimers.value.forEach((timer) => clearInterval(timer))
  pollingTimers.value.clear()
})
</script>

<template>
  <div class="task-history">
    <TaskCard v-for="task in tasks" :key="task.taskId" :task="task" />
    <el-empty v-if="!loading && tasks.length === 0" description="暂无翻译历史" :image-size="80" />
    <div v-if="loading" style="text-align: center; padding: 24px">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
    </div>
  </div>
</template>

<style scoped>
.task-history {
  max-width: 800px;
}
</style>
