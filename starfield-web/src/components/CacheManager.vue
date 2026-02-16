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

/** 编辑状态 */
const editingId = ref<number | null>(null)
const editingText = ref('')

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

/** 开始编辑 */
function startEdit(entry: CacheEntry) {
  editingId.value = entry.id
  editingText.value = entry.targetText
}

/** 取消编辑 */
function cancelEdit() {
  editingId.value = null
  editingText.value = ''
}

/** 保存编辑 */
async function saveEdit(entry: CacheEntry) {
  if (!editingText.value.trim()) {
    ElMessage.warning('译文不能为空')
    return
  }
  try {
    await updateCacheEntry(entry.id, editingText.value.trim())
    ElMessage.success('更新成功')
    editingId.value = null
    loadEntries()
  } catch {
    ElMessage.error('更新失败')
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
      <el-table-column label="译文" min-width="200">
        <template #default="{ row }">
          <div v-if="editingId === row.id" class="edit-cell">
            <el-input v-model="editingText" size="small" @keyup.enter="saveEdit(row)" />
            <el-button size="small" type="primary" @click="saveEdit(row)">保存</el-button>
            <el-button size="small" @click="cancelEdit">取消</el-button>
          </div>
          <span v-else class="text-cell" @click="startEdit(row)">{{ row.targetText }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="targetLang" label="目标语言" width="100" />
      <el-table-column label="更新时间" width="160">
        <template #default="{ row }">{{ formatTime(row.updatedAt) }}</template>
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
</template>

<style scoped>
.cache-desc { margin: 0 0 16px; font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.6; }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; }
.search-input { flex: 1; }
.edit-cell { display: flex; align-items: center; gap: 6px; }
.text-cell { cursor: pointer; }
.text-cell:hover { color: var(--el-color-primary); }
.pagination-wrap { margin-top: 16px; display: flex; justify-content: center; }
</style>
