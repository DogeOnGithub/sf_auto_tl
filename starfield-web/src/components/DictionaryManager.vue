<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getEntries, createEntry, updateEntry, deleteEntry } from '@/services/dictionaryApi'
import type { DictionaryEntry } from '@/types'

const entries = ref<DictionaryEntry[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loading = ref(false)
const dialogVisible = ref(false)
const isEditing = ref(false)
const editingId = ref<number | null>(null)
const submitting = ref(false)

const form = ref({ sourceText: '', targetText: '' })

let searchTimer: ReturnType<typeof setTimeout> | null = null

/**
 * 获取当前页词条
 * 删掉末页最后一条后当前页会变空，此时退到实际的最后一页重新查，避免停在空白页
 */
async function fetchEntries() {
  loading.value = true
  try {
    var result = await getEntries(currentPage.value, pageSize.value, keyword.value || undefined)
    if (result.records.length === 0 && result.total > 0 && currentPage.value > 1) {
      currentPage.value = Math.max(1, result.pages)
      result = await getEntries(currentPage.value, pageSize.value, keyword.value || undefined)
    }
    entries.value = result.records
    total.value = result.total
  } catch (err: any) {
    var msg = err?.response?.data?.message || '获取词条列表失败'
    ElMessage.error(msg)
  } finally {
    loading.value = false
  }
}

/** 搜索防抖，关键词变化后回到第一页 */
function debounceSearch() {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => {
    currentPage.value = 1
    fetchEntries()
  }, 300)
}

function handlePageChange(page: number) {
  currentPage.value = page
  fetchEntries()
}

function handleSizeChange(size: number) {
  pageSize.value = size
  currentPage.value = 1
  fetchEntries()
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
    // 列表按更新时间倒序，新增或编辑过的词条会排到第一页最前，跳回第一页才能看到
    currentPage.value = 1
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
    <div class="pagination-wrap" v-if="total > 0">
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

.pagination-wrap { margin-top: 16px; display: flex; justify-content: center; }
</style>
