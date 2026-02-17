<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import { getCacheEntries, updateCacheEntry } from '@/services/cacheApi'
import type { CacheEntry } from '@/types'

const entries = ref<CacheEntry[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loading = ref(false)

/** 编辑弹窗状态 */
const dialogVisible = ref(false)
const editingEntry = ref<CacheEntry | null>(null)
const editingText = ref('')
const submitting = ref(false)

/** 格式化时间 */
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  var d = new Date(dateStr)
  if (isNaN(d.getTime())) return dateStr
  var pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 加载缓存列表 */
async function loadEntries() {
  loading.value = true
  try {
    var res = await getCacheEntries(currentPage.value, pageSize.value, keyword.value || undefined)
    entries.value = res.records
    total.value = res.total
  } catch {
    ElMessage.error('加载缓存列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadEntries()
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadEntries()
}

/** 打开编辑弹窗 */
function openEditDialog(entry: CacheEntry) {
  editingEntry.value = entry
  editingText.value = entry.targetText
  dialogVisible.value = true
}

/** 保存编辑 */
async function saveEdit() {
  if (!editingText.value.trim()) {
    ElMessage.warning('译文不能为空')
    return
  }
  if (!editingEntry.value) return
  submitting.value = true
  try {
    await updateCacheEntry(editingEntry.value.id, editingText.value.trim())
    ElMessage.success('更新成功')
    dialogVisible.value = false
    loadEntries()
  } catch {
    ElMessage.error('更新失败')
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadEntries()
})
</script>

<template>
  <el-card header="翻译缓存管理">
    <p class="cache-desc">翻译缓存会在翻译时自动匹配已有译文，避免重复翻译相同词条，节省时间和 Token。你可以在此搜索和修正缓存中的译文。</p>
    <div class="toolbar">
      <el-input
        v-model="keyword"
        placeholder="搜索原文或译文..."
        clearable
        @keyup.enter="handleSearch"
        class="search-input"
      />
      <el-button type="primary" :icon="Search" @click="handleSearch">搜索</el-button>
    </div>
    <el-table :data="entries" v-loading="loading" empty-text="暂无缓存记录">
      <el-table-column prop="subrecordType" label="类型" width="120" />
      <el-table-column prop="sourceText" label="原文" min-width="200" show-overflow-tooltip />
      <el-table-column prop="targetText" label="译文" min-width="200" show-overflow-tooltip />
      <el-table-column prop="targetLang" label="目标语言" width="100" />
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80">
        <template #default="{ row }">
          <el-button size="small" @click="openEditDialog(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination-wrap" v-if="total > pageSize">
      <el-pagination
        layout="prev, pager, next"
        :total="total"
        :page-size="pageSize"
        :current-page="currentPage"
        @current-change="handlePageChange"
      />
    </div>
  </el-card>

  <el-dialog v-model="dialogVisible" title="编辑译文" width="560px">
    <div v-if="editingEntry" class="edit-form">
      <div class="edit-row">
        <span class="edit-label">类型</span>
        <span>{{ editingEntry.subrecordType }}</span>
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
.cache-desc { margin: 0 0 16px; font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.6; }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.search-input { flex: 1; }
.pagination-wrap { margin-top: 16px; display: flex; justify-content: center; }
.edit-form { display: flex; flex-direction: column; gap: 12px; }
.edit-row { display: flex; gap: 12px; align-items: flex-start; }
.edit-label { min-width: 40px; color: var(--el-text-color-secondary); flex-shrink: 0; }
</style>
