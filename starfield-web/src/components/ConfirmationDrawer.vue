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

/** 编辑弹窗相关 */
const dialogVisible = ref(false)
const editingEntry = ref<ConfirmationRecord | null>(null)
const editingText = ref('')
const submitting = ref(false)

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

/** 切换状态过滤 */
function setStatusFilter(val: string) {
  statusFilter.value = val
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

/** 打开编辑弹窗 */
function openEditDialog(row: ConfirmationRecord) {
  editingEntry.value = row
  editingText.value = row.targetText
  dialogVisible.value = true
}

/** 保存编辑 */
async function saveEdit() {
  if (!editingEntry.value) return
  submitting.value = true
  try {
    var updated = await updateTargetText(editingEntry.value.id, editingText.value)
    editingEntry.value.targetText = updated.targetText
    editingEntry.value.updatedAt = updated.updatedAt
    dialogVisible.value = false
    ElMessage.success('译文已更新')
  } catch {
    ElMessage.error('更新失败')
  } finally {
    submitting.value = false
  }
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
          <el-tag type="warning" size="small">待确认 {{ pendingCount }} (当前页)</el-tag>
          <el-tag type="success" size="small">已确认 {{ confirmedCount }} (当前页)</el-tag>
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
      <div class="status-filters">
        <el-check-tag :checked="statusFilter === ''" @change="setStatusFilter('')">全部</el-check-tag>
        <el-check-tag :checked="statusFilter === 'pending'" @change="setStatusFilter('pending')">待确认</el-check-tag>
        <el-check-tag :checked="statusFilter === 'confirmed'" @change="setStatusFilter('confirmed')">已确认</el-check-tag>
      </div>
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
      <el-table-column label="类型" width="140">
        <template #default="{ row }">
          <span>{{ row.recordType }} → {{ row.recordId.split(':')[2] || '' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="sourceText" label="原文" min-width="200" show-overflow-tooltip />
      <el-table-column label="译文" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          <div class="text-cell">
            <span>{{ row.targetText }}</span>
            <el-button :icon="EditPen" size="small" link @click="openEditDialog(row)" />
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

  <el-dialog v-model="dialogVisible" title="编辑译文" width="560px" append-to-body>
    <div v-if="editingEntry" class="edit-form">
      <div class="edit-row">
        <span class="edit-label">类型</span>
        <span>{{ editingEntry.recordType }} → {{ editingEntry.recordId.split(':')[2] || '' }}</span>
      </div>
      <div class="edit-row">
        <span class="edit-label">原文</span>
        <span>{{ editingEntry.sourceText }}</span>
      </div>
      <div class="edit-row">
        <span class="edit-label">译文</span>
        <el-input v-model="editingText" type="textarea" :rows="3" placeholder="请输入译文" />
      </div>
    </div>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="saveEdit">确定</el-button>
    </template>
  </el-dialog>
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

.status-filters {
  display: flex;
  gap: 4px;
}

.text-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}

.text-cell span {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.edit-form {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.edit-row {
  display: flex;
  gap: 12px;
  align-items: flex-start;
}

.edit-label {
  min-width: 40px;
  color: var(--el-text-color-secondary);
  flex-shrink: 0;
}

.drawer-pagination {
  margin-top: 16px;
  display: flex;
  justify-content: center;
}
</style>
