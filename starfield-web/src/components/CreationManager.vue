<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, Link, Search, Edit, Upload, Download } from '@element-plus/icons-vue'
import { createCreation, getCreations, getCreation, updateCreation, deleteCreation, deleteCreationVersion, getCreationTasks, uploadPatch, uploadFile } from '@/services/creationApi'
import { downloadFile } from '@/services/taskApi'
import type { Creation, TaskResponse } from '@/types'

const creations = ref<Creation[]>([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(12)
const keyword = ref('')
const loading = ref(false)

/** 上传对话框 */
const showUploadDialog = ref(false)
const uploading = ref(false)
const form = ref({
  name: '',
  translatedName: '',
  author: '',
  ccLink: '',
  nexusLink: '',
  version: '',
  fileShareLink: '',
  remark: '',
  tags: '',
})
const modFile = ref<File | null>(null)
const imageFiles = ref<File[]>([])

/** 编辑对话框 */
const showEditDialog = ref(false)
const editing = ref(false)
const editForm = ref({
  id: 0,
  name: '',
  translatedName: '',
  author: '',
  ccLink: '',
  nexusLink: '',
  remark: '',
  tags: '',
})

/** 详情抽屉 */
const showDrawer = ref(false)
const selectedCreation = ref<Creation | null>(null)
const creationTasks = ref<TaskResponse[]>([])
const loadingTasks = ref(false)

/** 添加版本对话框 */
const showAddVersionDialog = ref(false)
const addingVersion = ref(false)
const versionForm = ref({
  version: '',
  fileShareLink: '',
})
const versionModFile = ref<File | null>(null)

/** 格式化时间 */
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  var d = new Date(dateStr)
  if (isNaN(d.getTime())) return dateStr
  var pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

/** 加载作品列表 */
async function loadCreations() {
  loading.value = true
  try {
    var res = await getCreations(currentPage.value, pageSize.value, keyword.value || undefined)
    creations.value = res.records
    total.value = res.total
  } catch {
    ElMessage.error('加载作品列表失败')
  } finally {
    loading.value = false
  }
}

function handleSearch() {
  currentPage.value = 1
  loadCreations()
}

function handlePageChange(page: number) {
  currentPage.value = page
  loadCreations()
}

/** 打开上传对话框 */
function openUploadDialog() {
  form.value = { name: '', translatedName: '', author: '', ccLink: '', nexusLink: '', version: '', fileShareLink: '', remark: '', tags: '' }
  modFile.value = null
  imageFiles.value = []
  showUploadDialog.value = true
}

function handleModFileChange(uploadFile: any) {
  modFile.value = uploadFile.raw
}

function handleImageChange(_uploadFile: any, uploadFiles: any[]) {
  imageFiles.value = uploadFiles.map((f: any) => f.raw)
}

function handleImageRemove(_uploadFile: any, uploadFiles: any[]) {
  imageFiles.value = uploadFiles.map((f: any) => f.raw)
}

/** 提交创建 */
async function handleSubmit() {
  if (!form.value.name.trim()) {
    ElMessage.warning('请输入名称')
    return
  }
  uploading.value = true
  try {
    var fd = new FormData()
    var data = {
      name: form.value.name,
      translatedName: form.value.translatedName || null,
      author: form.value.author || null,
      ccLink: form.value.ccLink || null,
      nexusLink: form.value.nexusLink || null,
      version: form.value.version || '1.0',
      fileShareLink: form.value.fileShareLink || null,
      remark: form.value.remark || null,
      tags: form.value.tags ? form.value.tags.split(',').map(t => t.trim()).filter(Boolean) : [],
    }
    fd.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }))
    if (modFile.value) fd.append('file', modFile.value)
    imageFiles.value.forEach(img => fd.append('images', img))

    await createCreation(fd)
    ElMessage.success('创建成功')
    showUploadDialog.value = false
    loadCreations()
  } catch (e: any) {
    var msg = e?.response?.data?.message || '创建失败'
    ElMessage.error(msg)
  } finally {
    uploading.value = false
  }
}

/** 打开编辑对话框 */
function openEditDialog(creation: Creation) {
  editForm.value = {
    id: creation.id,
    name: creation.name,
    translatedName: creation.translatedName || '',
    author: creation.author || '',
    ccLink: creation.ccLink || '',
    nexusLink: creation.nexusLink || '',
    remark: creation.remark || '',
    tags: creation.tags ? creation.tags.join(',') : '',
  }
  showEditDialog.value = true
}

/** 提交编辑 */
async function handleEditSubmit() {
  if (!editForm.value.name.trim()) {
    ElMessage.warning('请输入名称')
    return
  }
  editing.value = true
  try {
    var data = {
      name: editForm.value.name,
      translatedName: editForm.value.translatedName || null,
      author: editForm.value.author || null,
      ccLink: editForm.value.ccLink || null,
      nexusLink: editForm.value.nexusLink || null,
      remark: editForm.value.remark || null,
      tags: editForm.value.tags ? editForm.value.tags.split(',').map(t => t.trim()).filter(Boolean) : [],
    }
    var updated = await updateCreation(editForm.value.id, data)
    ElMessage.success('更新成功')
    showEditDialog.value = false
    // 刷新详情
    if (selectedCreation.value?.id === editForm.value.id) {
      selectedCreation.value = updated
    }
    loadCreations()
  } catch {
    ElMessage.error('更新失败')
  } finally {
    editing.value = false
  }
}

/** 打开详情抽屉 */
async function openDetail(creation: Creation) {
  try {
    var detail = await getCreation(creation.id)
    selectedCreation.value = detail
    showDrawer.value = true
    loadCreationTasks(creation.id)
  } catch {
    ElMessage.error('加载详情失败')
  }
}

/** 加载作品关联的翻译任务 */
async function loadCreationTasks(creationId: number) {
  loadingTasks.value = true
  try {
    creationTasks.value = await getCreationTasks(creationId)
  } catch {
    creationTasks.value = []
  } finally {
    loadingTasks.value = false
  }
}

/** 状态中文映射 */
function statusLabel(status: string): string {
  var map: Record<string, string> = {
    waiting: '等待中', parsing: '解析中', translating: '翻译中',
    assembling: '重组中', completed: '已完成', failed: '失败',
  }
  return map[status] ?? status
}

/** 状态标签类型 */
function statusTagType(status: string): string {
  var map: Record<string, string> = {
    waiting: 'info', parsing: 'warning', translating: '',
    assembling: 'warning', completed: 'success', failed: 'danger',
  }
  return map[status] ?? 'info'
}

/** 下载翻译文件 */
async function handleDownloadTranslated(taskId: string) {
  try {
    var res = await downloadFile(taskId)
    window.open(res.downloadUrl)
  } catch {
    ElMessage.error('下载失败')
  }
}

/** 删除作品 */
async function handleDelete(id: number) {
  try {
    await ElMessageBox.confirm('确定删除该作品及所有版本？', '提示', { type: 'warning' })
    await deleteCreation(id)
    ElMessage.success('删除成功')
    if (showDrawer.value && selectedCreation.value?.id === id) {
      showDrawer.value = false
    }
    loadCreations()
  } catch { /* 取消 */ }
}

/** 删除版本 */
async function handleDeleteVersion(versionId: number) {
  try {
    await ElMessageBox.confirm('确定删除该版本？', '提示', { type: 'warning' })
    await deleteCreationVersion(versionId)
    ElMessage.success('版本已删除')
    // 刷新详情
    if (selectedCreation.value) {
      var detail = await getCreation(selectedCreation.value.id)
      selectedCreation.value = detail
    }
    loadCreations()
  } catch { /* 取消 */ }
}

/** 获取最新版本号 */
function getLatestVersion(creation: Creation): string {
  if (!creation.versions || creation.versions.length === 0) return '-'
  return creation.versions[0].version
}

/** 打开添加版本对话框 */
function openAddVersionDialog() {
  versionForm.value = { version: '', fileShareLink: '' }
  versionModFile.value = null
  showAddVersionDialog.value = true
}

function handleVersionModFileChange(uploadFile: any) {
  versionModFile.value = uploadFile.raw
}

/** 提交添加版本 */
async function handleAddVersionSubmit() {
  if (!selectedCreation.value) return
  if (!versionForm.value.version.trim()) {
    ElMessage.warning('请输入版本号')
    return
  }
  addingVersion.value = true
  try {
    var fd = new FormData()
    var data = {
      name: selectedCreation.value.name,
      version: versionForm.value.version,
      fileShareLink: versionForm.value.fileShareLink || null,
    }
    fd.append('data', new Blob([JSON.stringify(data)], { type: 'application/json' }))
    if (versionModFile.value) fd.append('file', versionModFile.value)

    await createCreation(fd)
    ElMessage.success('版本添加成功')
    showAddVersionDialog.value = false
    // 刷新详情
    var detail = await getCreation(selectedCreation.value.id)
    selectedCreation.value = detail
    loadCreations()
  } catch (e: any) {
    var msg = e?.response?.data?.message || '添加版本失败'
    ElMessage.error(msg)
  } finally {
    addingVersion.value = false
  }
}

/** 上传汉化补丁 */
async function handleUploadPatch(versionId: number, uploadFile: any) {
  try {
    var result = await uploadPatch(versionId, uploadFile.raw)
    ElMessage.success('补丁上传成功')
    if (selectedCreation.value) {
      selectedCreation.value = result as any
    }
    loadCreations()
  } catch {
    ElMessage.error('补丁上传失败')
  }
}

/** 下载汉化补丁 */
function handleDownloadPatch(patchFilePath: string) {
  window.open(patchFilePath)
}

/** 校验是否为有效 http 链接 */
function isValidUrl(url: string | null | undefined): boolean {
  if (!url) return false
  return url.startsWith('http://') || url.startsWith('https://')
}

/** 在新窗口打开外部链接 */
function openExternal(url: string) {
  window.open(url)
}

/** 上传/替换 Mod 文件 */
async function handleUploadFile(versionId: number, uploadFileObj: any) {
  try {
    var result = await uploadFile(versionId, uploadFileObj.raw)
    ElMessage.success('文件上传成功')
    if (selectedCreation.value) {
      selectedCreation.value = result as any
    }
    loadCreations()
  } catch {
    ElMessage.error('文件上传失败')
  }
}

onMounted(() => {
  loadCreations()
})
</script>

<template>
  <div class="creation-manager">
    <!-- 顶部操作栏 -->
    <div class="toolbar">
      <el-input v-model="keyword" placeholder="搜索名称、作者、标签..." :prefix-icon="Search" clearable style="width: 300px" @keyup.enter="handleSearch" @clear="handleSearch" />
      <el-button type="primary" :icon="Plus" @click="openUploadDialog">分享 Mod</el-button>
    </div>

    <!-- 卡片列表 -->
    <div v-loading="loading" class="card-grid">
      <el-card v-for="item in creations" :key="item.id" shadow="hover" class="creation-card" @click="openDetail(item)">
        <div class="card-cover">
          <el-image v-if="item.images && item.images.length > 0" :src="item.images[0].url" fit="cover" class="card-image" />
          <div v-else class="card-placeholder">暂无图片</div>
        </div>
        <div class="card-body">
          <div class="card-title">{{ item.name }}</div>
          <div v-if="item.translatedName" class="card-subtitle">{{ item.translatedName }}</div>
          <div class="card-meta">
            <span v-if="item.author">{{ item.author }}</span>
            <span v-if="item.versions && item.versions.length > 0">v{{ getLatestVersion(item) }}</span>
            <span v-if="item.versions && item.versions.length > 1" class="version-count">{{ item.versions.length }} 个版本</span>
          </div>
          <div v-if="item.tags && item.tags.length > 0" class="card-tags">
            <el-tag v-for="tag in item.tags.slice(0, 3)" :key="tag" size="small" type="info">{{ tag }}</el-tag>
          </div>
        </div>
      </el-card>
    </div>

    <el-empty v-if="!loading && creations.length === 0" description="暂无作品" />

    <!-- 分页 -->
    <div v-if="total > pageSize" class="pagination">
      <el-pagination :current-page="currentPage" :page-size="pageSize" :total="total" layout="prev, pager, next" @current-change="handlePageChange" />
    </div>

    <!-- 上传对话框 -->
    <el-dialog v-model="showUploadDialog" title="上传 Mod" width="600px" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="Mod 名称（同名自动归为同一作品）" />
        </el-form-item>
        <el-form-item label="译名">
          <el-input v-model="form.translatedName" placeholder="中文译名" />
        </el-form-item>
        <el-form-item label="作者">
          <el-input v-model="form.author" placeholder="Mod 作者" />
        </el-form-item>
        <el-form-item label="CC 链接">
          <el-input v-model="form.ccLink" placeholder="Creation Club 链接" />
        </el-form-item>
        <el-form-item label="N 网链接">
          <el-input v-model="form.nexusLink" placeholder="Nexus Mods 链接" />
        </el-form-item>
        <el-form-item label="版本" required>
          <el-input v-model="form.version" placeholder="版本号（如 1.0）" />
        </el-form-item>
        <el-form-item label="Mod 文件">
          <el-upload :auto-upload="false" :limit="1" :on-change="handleModFileChange" :show-file-list="true">
            <el-button>选择文件</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item label="图片">
          <el-upload :auto-upload="false" list-type="picture-card" :on-change="handleImageChange" :on-remove="handleImageRemove" accept="image/*" multiple>
            <el-icon><Plus /></el-icon>
          </el-upload>
        </el-form-item>
        <el-form-item label="分享链接">
          <el-input v-model="form.fileShareLink" placeholder="文件分享链接" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="form.tags" placeholder="多个标签用逗号分隔" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" placeholder="备注信息" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showUploadDialog = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="handleSubmit">提交</el-button>
      </template>
    </el-dialog>

    <!-- 编辑对话框 -->
    <el-dialog v-model="showEditDialog" title="编辑作品信息" width="600px" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item label="名称" required>
          <el-input v-model="editForm.name" />
        </el-form-item>
        <el-form-item label="译名">
          <el-input v-model="editForm.translatedName" />
        </el-form-item>
        <el-form-item label="作者">
          <el-input v-model="editForm.author" />
        </el-form-item>
        <el-form-item label="CC 链接">
          <el-input v-model="editForm.ccLink" />
        </el-form-item>
        <el-form-item label="N 网链接">
          <el-input v-model="editForm.nexusLink" />
        </el-form-item>
        <el-form-item label="标签">
          <el-input v-model="editForm.tags" placeholder="多个标签用逗号分隔" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="editForm.remark" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showEditDialog = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="handleEditSubmit">保存</el-button>
      </template>
    </el-dialog>

    <!-- 详情抽屉 -->
    <el-drawer v-model="showDrawer" title="创作详情" size="900px">
      <template v-if="selectedCreation">
        <div v-if="selectedCreation.images && selectedCreation.images.length > 0" class="detail-section">
          <div class="detail-images-scroll">
            <el-image v-for="img in selectedCreation.images" :key="img.id" :src="img.url" fit="cover" class="detail-image" :preview-src-list="selectedCreation.images.map(i => i.url)" :preview-teleported="true" :z-index="3000" />
          </div>
        </div>

        <div class="detail-section">
          <div class="detail-header">
            <h4>基本信息</h4>
            <el-button text type="primary" :icon="Edit" @click="openEditDialog(selectedCreation!)">编辑</el-button>
          </div>
          <el-descriptions :column="1" border>
            <el-descriptions-item label="名称">{{ selectedCreation.name }}</el-descriptions-item>
            <el-descriptions-item label="译名">{{ selectedCreation.translatedName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="作者">{{ selectedCreation.author || '-' }}</el-descriptions-item>
            <el-descriptions-item label="链接">
              <div style="display: flex; gap: 8px;">
                <el-button v-if="isValidUrl(selectedCreation.ccLink)" text type="primary" size="small" :icon="Link" @click.stop="openExternal(selectedCreation.ccLink)">CC 链接</el-button>
                <el-button v-if="isValidUrl(selectedCreation.nexusLink)" text type="primary" size="small" :icon="Link" @click.stop="openExternal(selectedCreation.nexusLink)">N 网链接</el-button>
                <span v-if="!isValidUrl(selectedCreation.ccLink) && !isValidUrl(selectedCreation.nexusLink)">-</span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatTime(selectedCreation.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatTime(selectedCreation.updatedAt) }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="detail-section">
          <div class="detail-header">
            <h4>版本</h4>
            <el-button text type="primary" :icon="Upload" @click="openAddVersionDialog">添加版本</el-button>
          </div>
          <el-table :data="selectedCreation.versions" border size="small" style="width: 100%">
            <el-table-column prop="version" label="版本" width="80" />
            <el-table-column label="Mod 文件" min-width="180">
              <template #default="{ row }">
                <div style="display: flex; align-items: center; gap: 4px;">
                  <el-tooltip v-if="row.fileName" :content="row.fileName" placement="top" :show-after="300">
                    <span class="patch-filename">{{ row.fileName }}</span>
                  </el-tooltip>
                  <el-button v-if="row.filePath" text type="primary" size="small" :icon="Download" @click.stop="openExternal(row.filePath)">下载</el-button>
                  <el-upload :auto-upload="false" :show-file-list="false" :limit="1" :on-change="(f: any) => handleUploadFile(row.id, f)">
                    <el-button text type="success" size="small" :icon="Upload">{{ row.filePath ? '替换' : '上传' }}</el-button>
                  </el-upload>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="分享链接">
              <template #default="{ row }">
                <el-tooltip v-if="isValidUrl(row.fileShareLink)" :content="row.fileShareLink" placement="top" :show-after="300">
                  <a :href="row.fileShareLink" target="_blank"><el-icon><Link /></el-icon> 打开</a>
                </el-tooltip>
                <span v-else>-</span>
              </template>
            </el-table-column>
            <el-table-column label="汉化补丁" min-width="180">
              <template #default="{ row }">
                <div style="display: flex; align-items: center; gap: 4px;">
                  <el-tooltip v-if="row.patchFileName" :content="row.patchFileName" placement="top" :show-after="300">
                    <span class="patch-filename">{{ row.patchFileName }}</span>
                  </el-tooltip>
                  <el-button v-if="row.patchFilePath" text type="primary" size="small" :icon="Download" @click.stop="handleDownloadPatch(row.patchFilePath)">下载</el-button>
                  <el-upload :auto-upload="false" :show-file-list="false" :limit="1" :on-change="(f: any) => handleUploadPatch(row.id, f)">
                    <el-button text type="success" size="small" :icon="Upload">{{ row.patchFilePath ? '替换' : '上传' }}</el-button>
                  </el-upload>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="上传时间" width="160">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="70" align="center">
              <template #default="{ row }">
                <el-button text type="danger" size="small" @click.stop="handleDeleteVersion(row.id)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div v-if="creationTasks.length > 0" class="detail-section">
          <h4>AI 翻译</h4>
          <el-table :data="creationTasks" border size="small" style="width: 100%">
              <el-table-column label="文件" min-width="120">
                <template #default="{ row }">{{ row.fileName }}</template>
              </el-table-column>
              <el-table-column label="版本" width="70">
                <template #default="{ row }">{{ row.creation?.version ? 'v' + row.creation.version : '-' }}</template>
              </el-table-column>
              <el-table-column label="状态" width="80">
                <template #default="{ row }">
                  <el-tag :type="statusTagType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="150">
                <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80" align="center">
                <template #default="{ row }">
                  <el-button v-if="row.status === 'completed'" text type="primary" size="small" :icon="Download" @click.stop="handleDownloadTranslated(row.taskId)">下载</el-button>
                </template>
              </el-table-column>
            </el-table>
        </div>

        <div v-if="selectedCreation.tags && selectedCreation.tags.length > 0" class="detail-section">
          <h4>标签</h4>
          <div class="detail-tags">
            <el-tag v-for="tag in selectedCreation.tags" :key="tag" type="info">{{ tag }}</el-tag>
          </div>
        </div>

        <div v-if="selectedCreation.remark" class="detail-section">
          <h4>备注</h4>
          <p class="detail-remark">{{ selectedCreation.remark }}</p>
        </div>

        <div class="detail-actions">
          <el-button type="danger" :icon="Delete" @click="handleDelete(selectedCreation.id)">不再分享</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 添加版本对话框 -->
    <el-dialog v-model="showAddVersionDialog" title="添加版本" width="500px" :close-on-click-modal="false">
      <el-form label-width="100px">
        <el-form-item label="版本号" required>
          <el-input v-model="versionForm.version" placeholder="版本号（如 1.1）" />
        </el-form-item>
        <el-form-item label="Mod 文件">
          <el-upload :auto-upload="false" :limit="1" :on-change="handleVersionModFileChange" :show-file-list="true">
            <el-button>选择文件</el-button>
          </el-upload>
        </el-form-item>
        <el-form-item label="分享链接">
          <el-input v-model="versionForm.fileShareLink" placeholder="文件分享链接" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showAddVersionDialog = false">取消</el-button>
        <el-button type="primary" :loading="addingVersion" @click="handleAddVersionSubmit">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 16px; }
.creation-card { cursor: pointer; transition: transform 0.2s; }
.creation-card:hover { transform: translateY(-2px); }
.creation-card :deep(.el-card__body) { padding: 0; }
.card-cover { height: 140px; overflow: hidden; background: var(--el-fill-color-lighter); }
.card-image { width: 100%; height: 140px; display: block; }
.card-placeholder { height: 140px; display: flex; align-items: center; justify-content: center; color: var(--el-text-color-placeholder); font-size: 13px; }
.card-body { padding: 12px; }
.card-title { font-weight: 600; font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-subtitle { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-meta { display: flex; gap: 8px; font-size: 12px; color: var(--el-text-color-placeholder); margin-top: 6px; }
.card-tags { display: flex; gap: 4px; flex-wrap: wrap; margin-top: 8px; }
.pagination { display: flex; justify-content: center; margin-top: 24px; }
.detail-section { margin-bottom: 20px; }
.detail-section h4 { margin: 0 0 8px; font-size: 14px; color: var(--el-text-color-primary); }
.detail-header { display: flex; justify-content: space-between; align-items: center; }
.detail-tags { display: flex; gap: 6px; flex-wrap: wrap; }
.detail-images-scroll { display: flex; gap: 8px; overflow-x: auto; padding-bottom: 4px; }
.detail-images-scroll::-webkit-scrollbar { height: 6px; }
.detail-images-scroll::-webkit-scrollbar-thumb { background: var(--el-border-color); border-radius: 3px; }
.detail-image { flex-shrink: 0; width: 160px; height: 120px; border-radius: 4px; }
.detail-remark { margin: 0; font-size: 13px; color: var(--el-text-color-regular); white-space: pre-wrap; }
.detail-actions { margin-top: 24px; padding-top: 16px; border-top: 1px solid var(--el-border-color-lighter); }
.patch-filename { font-size: 12px; color: var(--el-text-color-regular); max-width: 120px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
