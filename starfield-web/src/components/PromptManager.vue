<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Edit, Delete } from '@element-plus/icons-vue'
import { listPrompts, createPrompt, updatePrompt, deletePrompt } from '@/services/promptApi'
import type { PromptItem } from '@/types'

const prompts = ref<PromptItem[]>([])
const loading = ref(false)

/** 对话框状态 */
const showDialog = ref(false)
const dialogTitle = ref('新建 Prompt')
const saving = ref(false)
const editingId = ref<number | null>(null)
const form = ref({ name: '', content: '' })

/** 格式化时间 */
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  var d = new Date(dateStr)
  if (isNaN(d.getTime())) return dateStr
  var pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 内容摘要（截取前 80 字符） */
function contentSummary(content: string): string {
  if (!content) return ''
  return content.length > 80 ? content.slice(0, 80) + '...' : content
}

/** 加载 Prompt 列表 */
async function loadPrompts() {
  loading.value = true
  try {
    prompts.value = await listPrompts()
  } catch {
    ElMessage.error('加载 Prompt 列表失败')
  } finally {
    loading.value = false
  }
}

/** 打开新建对话框 */
function openCreateDialog() {
  editingId.value = null
  dialogTitle.value = '新建 Prompt'
  form.value = { name: '', content: '' }
  showDialog.value = true
}

/** 打开编辑对话框 */
function openEditDialog(row: PromptItem) {
  editingId.value = row.id
  dialogTitle.value = '编辑 Prompt'
  form.value = { name: row.name, content: row.content }
  showDialog.value = true
}

/** 提交表单（新建或编辑） */
async function handleSubmit() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请输入 Prompt 名称')
    return
  }
  if (!form.value.content.trim()) {
    ElMessage.warning('请输入 Prompt 内容')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await updatePrompt(editingId.value, form.value)
      ElMessage.success('更新成功')
    } else {
      await createPrompt(form.value)
      ElMessage.success('创建成功')
    }
    showDialog.value = false
    loadPrompts()
  } catch (err: any) {
    var msg = err?.response?.data?.message || '操作失败'
    ElMessage.error(msg)
  } finally {
    saving.value = false
  }
}

/** 删除 Prompt */
async function handleDelete(row: PromptItem) {
  try {
    await ElMessageBox.confirm(`确定删除 Prompt「${row.name}」？`, '提示', { type: 'warning' })
    await deletePrompt(row.id)
    ElMessage.success('删除成功')
    loadPrompts()
  } catch { /* 取消 */ }
}

onMounted(loadPrompts)
</script>

<template>
  <div class="prompt-manager">
    <div class="toolbar">
      <el-button type="primary" :icon="Plus" @click="openCreateDialog">新建 Prompt</el-button>
    </div>

    <el-table :data="prompts" v-loading="loading" border stripe style="width: 100%">
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column label="内容摘要" min-width="240">
        <template #default="{ row }">
          <span class="content-summary">{{ contentSummary(row.content) }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="usageCount" label="使用次数" width="100" align="center" />
      <el-table-column label="更新时间" width="160" align="center">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" align="center">
        <template #default="{ row }">
          <el-button text type="primary" size="small" :icon="Edit" @click="openEditDialog(row)">编辑</el-button>
          <el-button text type="danger" size="small" :icon="Delete" @click="handleDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="showDialog" :title="dialogTitle" width="600px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="请输入 Prompt 名称" maxlength="255" />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input v-model="form.content" type="textarea" :rows="10" placeholder="请输入 Prompt 内容" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="handleSubmit">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar {
  margin-bottom: 16px;
}

.content-summary {
  color: var(--el-text-color-regular);
  font-size: 13px;
}
</style>
