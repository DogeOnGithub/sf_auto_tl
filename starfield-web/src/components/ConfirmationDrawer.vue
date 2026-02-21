<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, EditPen } from '@element-plus/icons-vue'
import {
  listConfirmations,
  updateTargetText,
  confirmSingle,
  batchConfirm,
  confirmAll,
  generateFile,
} from '@/services/confirmationApi'
import type { ConfirmationRecord } from '@/types'

const props = defineProps<{
  visible: boolean
  taskId: string
  fileName: string
}>()

const emit = defineEmits<{
  'update:visible': [val: boolean]
  'generated': []
}>()

const loading = ref(false)
const records = ref<ConfirmationRecord[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const statusFilter = ref('')
const keyword = ref('')
const selectedIds = ref<number[]>([])

/** 编辑相关 */
const editingId = ref<number | null>(null)
const editingText = ref('')

/** 统计 */
const pendingCount = computed(() => records.value.filter(r => r.status === 'pending').length)
const confirmedCount = computed(() => records.value.filter(r => r.status === 'confirmed').length)

/** 加载数据 */
async function loadData() {
  loading.value = true
  try {
    var res = await listConfirmations(
      props.taskId,
      currentPage.value,
      pageSize.value,
      statusFilter.value || undefined,
      keyword.value || undefined,
    )
    records.value = res.records
    total.value = res.total
  } catch {
    ElMessage.error('加载确认记录失败')
  } finally {
    loading.value = false
  }
}

/** 抽屉打开时加载 */
watch(() => props.visible, (val) => {
  if (val) {
    currentPage.value = 1
    selectedIds.value = []
    loadData()
  }
})

/** 搜索 */
function handleSearch() {
  currentPage.value = 1
  loadData()
}

/** 分页变化 */
function handlePageChange(page: number) {
  currentPage.value = page
  loadData()
}

function handleSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
  loadData()
}

/** 勾选变化 */
function handleSelectionChange(rows: ConfirmationRecord[]) {
  selectedIds.value = rows.map(r => r.id)
}

/** 开始编辑 */
function startEdit(row: ConfirmationRecord) {
  editingId.value = row.id
  editingText.value = row.targetText
}

/** 保存编辑 */
async function saveEdit(row: ConfirmationRecord) {
  try {
    var updated = await updateTargetText(row.id, editingText.value)
    row.targetText = updated.targetText
    row.updatedAt = updated.updatedAt
    editingId.value = null
    ElMessage.success('译文已更新')
  } catch {
    ElMessage.error('更新失败')
  }
}

/** 取消编辑 */
function cancelEdit() {
  editingId.value = null
}

/** 逐条确认 */
async function handleConfirmSingle(row: ConfirmationRecord) {
  try {
    await confirmSingle(props.taskId, row.id)
    row.status = 'confirmed'
    ElMessage.success('已确认')
  } catch {
    ElMessage.error('确认失败')
  }
}

/** 批量确认选中 */
async function handleBatchConfirm() {
  if (selectedIds.value.length === 0) {
    ElMessage.warning('请先勾选要确认的记录')
    return
  }
  try {
    await batchConfirm(props.taskId, selectedIds.value)
    ElMessage.success(`已确认 ${selectedIds.value.length} 条`)
    selectedIds.value = []
    loadData()
  } catch {
    ElMessage.error('批量确认失败')
  }
}

/** 全部确认 */
async function handleConfirmAll() {
  try {
    await ElMessageBox.confirm('确定全部确认？', '全部确认', { type: 'warning' })
    await confirmAll(props.taskId)
    ElMessage.success('已全部确认')
    loadData()
  } catch { /* 取消 */ }
}

/** 生成文件 */
async function handleGenerate() {
  try {
    await ElMessageBox.confirm('确认所有译文后将生成翻译文件，是否继续？', '生成文件', { type: 'info' })
    await generateFile(props.taskId)
    ElMessage.success('文件生成已提交')
    emit('update:visible', false)
    emit('generated')
  } catch (err: any) {
    var msg = err?.response?.data?.message
    if (msg) {
      ElMessage.error(msg)
    }
  }
}

function handleClose() {
  emit('update:visible', false)
}
</script>

<template>
  <el-drawer
    :model-value="visible"
    title="翻译确认"
    size="75%"
    @close="handleClose"
  >
    <template #header>
      <div class="drawer-header">
        <span class="drawer-title">翻译确认 - {{ fileName }}</span>
        <div class="drawer-stats">
          <el-tag type="warning" size="small">待确认 {{ pendingCount }}</el-tag>
          <el-tag type="success" size="small">已确认 {{ confirmedCount }}</el-tag>
          <el-tag size="small">共 {{ total }} 条</el-tag>
        </div>
      </div>
    </template>

    <div class="drawer-toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索原文/译文"
        clearable
        style="width: 220px"
        @keyup.enter="handleSearch"
        @clear="handleSearch"
      />
      <el-select v-model="statusFilter" placeholder="状态过滤" clearable style="width: 140px" @change="handleSearch">
        <el-option label="待确认" value="pending" />
        <el-option label="已确认" value="confirmed" />
      </el-select>
      <el-button @click="handleSearch">搜索</el-button>
      <div class="toolbar-right">
        <el-button :disabled="selectedIds.length === 0" @click="handleBatchConfirm">
          确认选中 ({{ selectedIds.length }})
        </el-button>
        <el-button type="warning" @click="handleConfirmAll">全部确认</el-button>
        <el-button type="primary" @click="handleGenerate">生成文件</el-button>
      </div>
    </div>

    <el-table
      v-loading="loading"
      :data="records"
      border
      style="width: 100%"
      @selection-change="handleSelectionChange"
    >
      <el-table-column type="selection" width="40" />
      <el-table-column prop="recordType" label="类型" width="100" />
      <el-table-column prop="sourceText" label="原文" min-width="200" show-overflow-tooltip />
      <el-table-column label="译文" min-width="200">
        <template #default="{ row }">
          <div v-if="editingId === row.id" class="edit-cell">
            <el-input
              v-model="editingText"
              type="textarea"
              :autosize="{ minRows: 1, maxRows: 4 }"
              size="small"
            />
            <div class="edit-actions">
              <el-button size="small" type="primary" @click="saveEdit(row)">保存</el-button>
              <el-button size="small" @click="cancelEdit">取消</el-button>
            </div>
          </div>
          <div v-else class="text-cell" @dblclick="startEdit(row)">
            <span>{{ row.targetText }}</span>
            <el-button :icon="EditPen" size="small" link @click="startEdit(row)" />
          </div>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="90" align="center">
        <template #default="{ row }">
          <el-tag :type="row.status === 'confirmed' ? 'success' : 'warning'" size="small">
            {{ row.status === 'confirmed' ? '已确认' : '待确认' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="80" align="center">
        <template #default="{ row }">
          <el-button
            v-if="row.status === 'pending'"
            :icon="Check"
            size="small"
            type="success"
            link
            @click="handleConfirmSingle(row)"
          />
          <el-tag v-else type="success" size="small" effect="plain">✓</el-tag>
        </template>
      </el-table-column>
    </el-table>

    <div class="drawer-pagination">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        @current-change="handlePageChange"
        @size-change="handleSizeChange"
      />
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.drawer-title {
  font-size: 16px;
  font-weight: 600;
}

.drawer-stats {
  display: flex;
  gap: 8px;
}

.drawer-toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  flex-wrap: wrap;
}

.toolbar-right {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

.edit-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.edit-actions {
  display: flex;
  gap: 4px;
}

.text-cell {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.text-cell span {
  flex: 1;
}

.drawer-pagination {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}
</style>
