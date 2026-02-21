<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getTask, getTasksPaged, batchExpireTasks } from '@/services/taskApi'
import { useStarborn } from '@/composables/useStarborn'
import type { TaskResponse } from '@/types'
import TaskCard from './TaskCard.vue'
import ConfirmationDrawer from './ConfirmationDrawer.vue'
import { Loading, Delete } from '@element-plus/icons-vue'

const { isStarborn } = useStarborn()

const tasks = ref<TaskResponse[]>([])
const pollingTimers = ref<Map<string, ReturnType<typeof setInterval>>>(new Map())
const loading = ref(false)

/** 分页状态 */
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

/** 勾选状态 */
const selectedIds = ref<Set<string>>(new Set())

/** 确认抽屉状态 */
const confirmationVisible = ref(false)
const confirmationTaskId = ref('')
const confirmationFileName = ref('')

const terminalStatuses = new Set(['completed', 'failed', 'expired', 'pending_confirmation'])

function isTerminal(status: string): boolean {
  return terminalStatuses.has(status)
}

/** 当前页可清理的任务 ID 列表 */
const expirableIds = computed(() =>
  tasks.value
    .filter((t) => t.status === 'completed' || t.status === 'failed' || t.status === 'pending_confirmation')
    .map((t) => t.taskId)
)

/** 是否全选 */
const isAllSelected = computed(() =>
  expirableIds.value.length > 0 && expirableIds.value.every((id) => selectedIds.value.has(id))
)

/** 半选状态 */
const isIndeterminate = computed(() => {
  var hasSelected = expirableIds.value.some((id) => selectedIds.value.has(id))
  return hasSelected && !isAllSelected.value
})

/** 切换全选 */
function toggleSelectAll(val: boolean) {
  if (val) {
    expirableIds.value.forEach((id) => selectedIds.value.add(id))
  } else {
    expirableIds.value.forEach((id) => selectedIds.value.delete(id))
  }
}

/** 切换单个选择 */
function toggleSelect(taskId: string, val: boolean) {
  if (val) {
    selectedIds.value.add(taskId)
  } else {
    selectedIds.value.delete(taskId)
  }
}

/** 任务是否可勾选（completed/failed/pending_confirmation 且未关联 creation） */
function isExpirable(task: TaskResponse): boolean {
  return (task.status === 'completed' || task.status === 'failed' || task.status === 'pending_confirmation') && !task.creation
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
  pollingTimers.value.forEach((timer) => clearInterval(timer))
  pollingTimers.value.clear()
  selectedIds.value.clear()

  loading.value = true
  try {
    var res = await getTasksPaged(currentPage.value, pageSize.value)
    tasks.value = res.records
    total.value = res.total
    res.records.filter((t) => !isTerminal(t.status)).forEach((t) => startPolling(t.taskId))
  } catch {
    // 静默处理
  } finally {
    loading.value = false
  }
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadTasks()
}

async function handleTaskExpired(taskId: string) {
  try {
    var updated = await getTask(taskId)
    var idx = tasks.value.findIndex((t) => t.taskId === taskId)
    if (idx !== -1) {
      tasks.value[idx] = updated
    }
    selectedIds.value.delete(taskId)
  } catch {
    // 静默处理
  }
}

/** 批量清理选中的任务 */
async function handleBatchExpire() {
  var ids = [...selectedIds.value]
  if (ids.length === 0) {
    ElMessage.info('请先勾选要清理的任务')
    return
  }

  try {
    await ElMessageBox.confirm(
      `确定清理选中的 ${ids.length} 个任务？COS 文件将被删除且无法恢复`,
      '批量清理确认',
      { type: 'warning' }
    )
    var res = await batchExpireTasks(ids)
    ElMessage.success(`已清理 ${res.expiredCount} 个任务`)
    loadTasks()
  } catch { /* 取消 */ }
}

/** 打开翻译确认抽屉 */
function handleOpenConfirmation(taskId: string, fileName: string) {
  confirmationTaskId.value = taskId
  confirmationFileName.value = fileName
  confirmationVisible.value = true
}

/** 文件生成完成后刷新任务列表 */
function handleGenerated() {
  loadTasks()
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
    <div v-if="isStarborn && tasks.length > 0" class="toolbar">
      <el-checkbox
        :model-value="isAllSelected"
        :indeterminate="isIndeterminate"
        @change="toggleSelectAll"
      >
        全选可清理
      </el-checkbox>
      <el-button
        type="danger"
        size="small"
        :icon="Delete"
        :disabled="selectedIds.size === 0"
        @click="handleBatchExpire"
      >
        批量清理 ({{ selectedIds.size }})
      </el-button>
    </div>

    <div v-for="task in tasks" :key="task.taskId" class="task-row">
      <el-checkbox
        v-if="isStarborn && isExpirable(task)"
        :model-value="selectedIds.has(task.taskId)"
        class="task-checkbox"
        @change="(val: boolean) => toggleSelect(task.taskId, val)"
      />
      <div v-else-if="isStarborn" class="task-checkbox-placeholder" />
      <TaskCard class="task-card-item" :task="task" @task-expired="handleTaskExpired" @open-confirmation="handleOpenConfirmation" />
    </div>

    <el-empty v-if="!loading && tasks.length === 0" description="暂无翻译历史" :image-size="80" />

    <div v-if="loading" style="text-align: center; padding: 24px">
      <el-icon class="is-loading" :size="24"><Loading /></el-icon>
    </div>

    <div v-if="total > pageSize" class="pagination">
      <el-pagination
        :current-page="currentPage"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next"
        @current-change="handlePageChange"
      />
    </div>

    <ConfirmationDrawer
      v-model:visible="confirmationVisible"
      :task-id="confirmationTaskId"
      :file-name="confirmationFileName"
      @generated="handleGenerated"
    />
  </div>
</template>

<style scoped>
.task-history {
  width: 100%;
}

.toolbar {
  margin-bottom: 12px;
  display: flex;
  align-items: center;
  gap: 16px;
}

.task-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}

.task-checkbox {
  margin-top: 18px;
  flex-shrink: 0;
}

.task-checkbox-placeholder {
  width: 22px;
  flex-shrink: 0;
}

.task-card-item {
  flex: 1;
  min-width: 0;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 16px;
}
</style>
