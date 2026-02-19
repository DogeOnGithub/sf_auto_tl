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

    <el-alert type="info" :closable="false" style="margin-bottom: 16px">
      <template #title>
        <span style="font-weight: 600">Prompt 拼装规则</span>
      </template>
      <div class="prompt-help">
        <p>翻译时的完整 Prompt 由以下部分按顺序拼装：</p>
        <ol>
          <li><b>基础指令</b>：使用自定义 Prompt（如已选择），否则使用默认 Prompt</li>
          <li><b>词典约束</b>：如果配置了词典词条，会追加「以下词条必须保持指定翻译」段落</li>
          <li><b>待翻译文本</b>：自动编号，格式为 <code>[1] 原文1</code> <code>[2] 原文2</code> ...，LLM 需按相同编号格式返回译文</li>
        </ol>
        <p style="margin: 8px 0 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6">
          💡 标签保护：原文中的 <code>&lt;...&gt;</code> 标签（如 <code>&lt;Alias=Player&gt;</code>、<code>&lt;Global=SQ_Companions01&gt;</code>）会在翻译前自动替换为占位符，翻译完成后还原，确保标签内容不被 LLM 修改。
        </p>
        <el-collapse>
          <el-collapse-item title="查看默认 Prompt 完整内容">
            <pre class="default-prompt">你是一个专业的游戏本地化翻译专家。请将以下 Starfield（星空）游戏 Mod 文本翻译为简体中文。

严格规则（必须遵守）：
1. 输入格式为编号行 [1] 原文1 [2] 原文2 ...，输出必须严格按相同编号格式 [1] 译文1 [2] 译文2 ...
2. 输出行数必须与输入行数完全一致，不得合并、拆分或遗漏任何一行
3. 每行只输出 [编号] 译文，绝对不要添加任何解释、注释、括号备注或额外内容
4. 禁止在译文后面添加（注：...）、(Note:...) 等任何形式的注释
5. &lt;&gt; 包裹的标签是占位符，必须原样保留不翻译，例如 &lt;alias&gt; &lt;br&gt; &lt;Global=SQ_Companions01&gt;

翻译要求：
1. 保持游戏术语的一致性和准确性
2. 翻译应自然流畅，符合中文游戏玩家的阅读习惯
3. 保留原文中的格式标记、变量占位符和特殊符号不做翻译
4. 对于专有名词，如无明确译法则保留原文</pre>
          </el-collapse-item>
        </el-collapse>
      </div>
    </el-alert>

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

.prompt-help p {
  margin: 4px 0 8px;
}

.prompt-help ol {
  margin: 0 0 8px;
  padding-left: 20px;
}

.prompt-help ol li {
  margin-bottom: 4px;
  line-height: 1.6;
}

.default-prompt {
  background: var(--el-fill-color-light);
  padding: 12px;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  margin: 0;
}
</style>
