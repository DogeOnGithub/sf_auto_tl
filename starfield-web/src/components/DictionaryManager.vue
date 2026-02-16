<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getEntries, createEntry, updateEntry, deleteEntry } from '@/services/dictionaryApi'
import type { DictionaryEntry } from '@/types'

const entries = ref<DictionaryEntry[]>([])
const keyword = ref('')
const loading = ref(false)
const dialogVisible = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)

const form = ref({ sourceText: '', targetText: '' })

let searchTimer: ReturnType<typeof setTimeout> | null = null

/** 获取词条列表 */
async function fetchEntries() {
  loading.value = true
  try {
    var result = await getEntries(keyword.value || undefined)
    entries.value = result.entries
  } catch (err: any) {
    var msg = err?.response?.data?.message || '获取词条列表失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}

/** 搜索防抖 */
function debounceSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    fetchEntries()
  }, 300)
}

/** 打开添加对话框 */
function showAddDialog() {
  isEditing.value = false
  editingId.value = null
  form.value = { sourceText: '', targetText: '' }
  dialogVisible.value = true
}

/** 打开编辑对话框 */
function editEntry(row: DictionaryEntry) {
  isEditing.value = true
  editingId.value = row.id
  form.value = { sourceText: row.sourceText, targetText: row.targetText }
  dialogVisible.value = true
}

/** 提交表单（添加或编辑） */
async function submitForm() {
  if (!form.value.sourceText.trim() || !form.value.targetText.trim()) {
    ElMessage.warning('原文和译文不能为空')
    return
  }
  submitting.value = true
  try {
    if (isEditing.value && editingId.value !== null) {
      await updateEntry(editingId.value, form.value.sourceText, form.value.targetText)
      ElMessage.success('词条更新成功')
    } else {
      await createEntry(form.value.sourceText, form.value.targetText)
      ElMessage.success('词条添加成功')
    }
    dialogVisible.value = false
    await fetchEntries()
  } catch (err: any) {
    var msg = err?.response?.data?.message || '操作失败'
    ElMessage.error(msg)
  } finally {
    submitting.value = false
  }
}

/** 删除确认 */
async function confirmDelete(row: DictionaryEntry) {
  try {
    await ElMessageBox.confirm(`确定删除词条「${row.sourceText}」？`, '删除确认', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      type: 'warning',
    })
    await deleteEntry(row.id)
    ElMessage.success('词条删除成功')
    await fetchEntries()
  } catch (err: any) {
    if (err === 'cancel') return
    var msg = err?.response?.data?.message || '删除失败'
    ElMessage.error(msg)
  }
}

onMounted(fetchEntries)
</script>

<template>
  <el-card header="固定词典管理">
    <p class="dict-desc">词典中的词条会在翻译时作为参考提供给 AI，确保特定术语、名称的翻译保持一致。例如将 "Starborn" 固定翻译为 "星裔"。</p>
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索词条..."
        clearable
        @input="debounceSearch"
        class="search-input"
      />
      <el-button type="primary" @click="showAddDialog">添加词条</el-button>
    </div>
    <el-table :data="entries" v-loading="loading" empty-text="暂无词条">
      <el-table-column prop="sourceText" label="原文" />
      <el-table-column prop="targetText" label="译文" />
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" @click="editEntry(row)">编辑</el-button>
          <el-button size="small" type="danger" @click="confirmDelete(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialogVisible" :title="isEditing ? '编辑词条' : '添加词条'" width="480px">
    <el-form label-width="60px">
      <el-form-item label="原文">
        <el-input v-model="form.sourceText" placeholder="请输入原文" />
      </el-form-item>
      <el-form-item label="译文">
        <el-input v-model="form.targetText" placeholder="请输入译文" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submitForm">确定</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.dict-desc { margin: 0 0 16px; font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.6; }
.toolbar {
  display: flex;
  gap: 12px;
  margin-bottom: 16px;
}

.search-input {
  flex: 1;
}
</style>
