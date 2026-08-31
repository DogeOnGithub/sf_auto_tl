<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh } from '@element-plus/icons-vue'
import {
  getPoolMembers,
  createPoolMember,
  updatePoolMember,
  deletePoolMember,
  testPoolMember,
  getPoolMemberUsage,
} from '@/services/llmPoolApi'
import type { LlmPoolDailyUsage, LlmPoolMember, LlmPoolMemberRequest } from '@/types'

const props = defineProps<{ isStarborn: boolean }>()

const members = ref<LlmPoolMember[]>([])
const loading = ref(false)

/** 正在验证连通性的成员 ID，用于只给当前行转圈 */
const testingId = ref<number | null>(null)

/** 编辑弹窗状态 */
const dialogVisible = ref(false)
const submitting = ref(false)
/** 为 null 表示新增 */
const editingId = ref<number | null>(null)
const form = ref<LlmPoolMemberRequest>(emptyForm())

/** 用量明细抽屉状态 */
const usageVisible = ref(false)
const usageLoading = ref(false)
const usageMember = ref<LlmPoolMember | null>(null)
const usageRows = ref<LlmPoolDailyUsage[]>([])

/** 错误归类到中文说明的映射，engine 侧的 lastErrorKind 取值 */
const ERROR_KIND_LABELS: Record<string, string> = {
  rate_limit: '限流',
  auth: '鉴权失效',
  quota: '余额不足',
  model_not_found: '模型不存在',
  transient: '网络或服务端异常',
  bad_request: '请求被拒绝',
}

/** 空表单，weight 默认 1、默认启用 */
function emptyForm(): LlmPoolMemberRequest {
  return { name: '', baseUrl: '', apiKey: '', model: '', weight: 1, enabled: true, remark: '' }
}

/** 窗口天数，取任一成员的统计口径，缺省按 7 天展示 */
const windowDays = computed(() => {
  var withStat = members.value.find(m => m.stat)
  return withStat?.stat?.windowDays ?? 7
})

/** 池里是否还有启用成员，没有的话默认额度整体不可用 */
const hasEnabledMember = computed(() => members.value.some(m => m.enabled))

/** 引擎是否给出了运行时状态，没有说明引擎不可达 */
const runtimeAvailable = computed(() => members.value.some(m => m.runtime))

/** 窗口内总 token，用来算各成员占比 */
const totalWindowTokens = computed(() =>
  members.value.reduce((sum, m) => sum + (m.stat?.windowTokens ?? 0), 0),
)

/** 成员在窗口内承担的成本占比，直观看出分散是否均匀 */
function sharePercent(member: LlmPoolMember): string {
  if (totalWindowTokens.value === 0) return '-'
  var pct = ((member.stat?.windowTokens ?? 0) / totalWindowTokens.value) * 100
  return `${pct.toFixed(1)}%`
}

/** 窗口内失败率 */
function failureRate(member: LlmPoolMember): string {
  var requests = member.stat?.windowRequests ?? 0
  if (requests === 0) return '-'
  var pct = ((member.stat?.windowFailures ?? 0) / requests) * 100
  return `${pct.toFixed(1)}%`
}

/** 失败率偏高时标红，便于一眼扫出坏成员 */
function isFailureRateHigh(member: LlmPoolMember): boolean {
  var requests = member.stat?.windowRequests ?? 0
  if (requests < 5) return false
  return (member.stat?.windowFailures ?? 0) / requests > 0.2
}

/** 大数字加千分位，token 数动辄百万级 */
function formatNumber(n: number | null | undefined): string {
  if (n === null || n === undefined) return '0'
  return n.toLocaleString('en-US')
}

/** 格式化时间 */
function formatTime(dateStr: string | null): string {
  if (!dateStr) return '-'
  var d = new Date(dateStr)
  if (isNaN(d.getTime())) return dateStr
  var pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 冷却剩余时间的可读文案 */
function cooldownText(seconds: number): string {
  if (seconds <= 0) return ''
  if (seconds < 60) return `${seconds} 秒`
  return `${Math.ceil(seconds / 60)} 分钟`
}

/** 成员当前状态：停用 / 冷却中 / 正常 / 未知（引擎不可达） */
function statusOf(member: LlmPoolMember): { type: 'info' | 'danger' | 'success' | 'warning'; text: string } {
  if (!member.enabled) return { type: 'info', text: '已停用' }
  if (!member.runtime) return { type: 'warning', text: '状态未知' }
  if (!member.runtime.available) {
    var kind = member.runtime.lastErrorKind
    var label = kind ? ERROR_KIND_LABELS[kind] ?? kind : '异常'
    return { type: 'danger', text: `冷却中 ${cooldownText(member.runtime.cooldownRemainingSeconds)}（${label}）` }
  }
  return { type: 'success', text: '正常' }
}

/** 最近失败原因的展示文案，优先用引擎内存里的实时值 */
function lastErrorText(member: LlmPoolMember): string {
  var runtime = member.runtime
  if (runtime?.lastErrorKind) {
    var label = ERROR_KIND_LABELS[runtime.lastErrorKind] ?? runtime.lastErrorKind
    return runtime.lastErrorMessage ? `${label}：${runtime.lastErrorMessage}` : label
  }
  return member.stat?.lastFailureReason ?? '-'
}

/** 加载成员列表 */
async function loadMembers() {
  loading.value = true
  try {
    members.value = await getPoolMembers()
  } catch {
    ElMessage.error('加载模型池失败')
  } finally {
    loading.value = false
  }
}

/** 打开新增弹窗 */
function openCreateDialog() {
  editingId.value = null
  form.value = emptyForm()
  dialogVisible.value = true
}

/** 打开编辑弹窗，Key 留空表示不修改 */
function openEditDialog(member: LlmPoolMember) {
  editingId.value = member.id
  form.value = {
    name: member.name,
    baseUrl: member.baseUrl,
    apiKey: '',
    model: member.model,
    weight: member.weight,
    enabled: member.enabled,
    remark: member.remark ?? '',
  }
  dialogVisible.value = true
}

/** 提交前校验必填项 */
function validateForm(): boolean {
  if (!form.value.name.trim()) {
    ElMessage.warning('请输入成员名')
    return false
  }
  if (!form.value.baseUrl.trim()) {
    ElMessage.warning('请输入 API 地址')
    return false
  }
  if (!form.value.model.trim()) {
    ElMessage.warning('请输入模型名称')
    return false
  }
  // 新增时 Key 必填；编辑时留空表示沿用原值，页面拿不到明文所以不能强制重填
  if (editingId.value === null && !(form.value.apiKey ?? '').trim()) {
    ElMessage.warning('请输入 API Key')
    return false
  }
  if (!form.value.weight || form.value.weight <= 0) {
    ElMessage.warning('配比必须为正整数')
    return false
  }
  return true
}

/** 保存新增或修改 */
async function handleSubmit() {
  if (!validateForm()) return
  submitting.value = true
  var payload: LlmPoolMemberRequest = {
    name: form.value.name.trim(),
    baseUrl: form.value.baseUrl.trim(),
    model: form.value.model.trim(),
    weight: form.value.weight,
    enabled: form.value.enabled,
    remark: (form.value.remark ?? '').trim() || undefined,
  }
  var key = (form.value.apiKey ?? '').trim()
  if (key) payload.apiKey = key

  try {
    if (editingId.value === null) {
      await createPoolMember(payload)
      ElMessage.success('新增成功')
    } else {
      await updatePoolMember(editingId.value, payload)
      ElMessage.success('修改成功')
    }
    dialogVisible.value = false
    loadMembers()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '保存失败')
  } finally {
    submitting.value = false
  }
}

/** 快速切换启用状态 */
async function handleToggleEnabled(member: LlmPoolMember, enabled: boolean) {
  try {
    await updatePoolMember(member.id, {
      name: member.name,
      baseUrl: member.baseUrl,
      model: member.model,
      weight: member.weight,
      enabled,
      remark: member.remark ?? undefined,
    })
    ElMessage.success(enabled ? '已启用' : '已停用')
    loadMembers()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '操作失败')
    loadMembers()
  }
}

/** 删除成员 */
async function handleDelete(member: LlmPoolMember) {
  try {
    await ElMessageBox.confirm(
      `确定删除成员「${member.name}」？它的用量统计会一并清除。`,
      '删除确认',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' },
    )
    await deletePoolMember(member.id)
    ElMessage.success('删除成功')
    loadMembers()
  } catch (err: any) {
    if (err === 'cancel') return
    ElMessage.error(err?.response?.data?.message || '删除失败')
  }
}

/** 验证成员连通性 */
async function handleTest(member: LlmPoolMember) {
  testingId.value = member.id
  try {
    var result = await testPoolMember(member.id)
    if (result.success) {
      ElMessage.success(`「${member.name}」连通正常，耗时 ${result.latencyMs} ms`)
    } else {
      ElMessageBox.alert(result.message, `「${member.name}」验证失败`, {
        confirmButtonText: '知道了',
        type: 'error',
      })
    }
    // 验证会产生一次真实调用，成功可解除冷却，刷新一下状态
    loadMembers()
  } catch (err: any) {
    ElMessage.error(err?.response?.data?.message || '验证请求失败')
  } finally {
    testingId.value = null
  }
}

/** 打开每日用量抽屉 */
async function openUsage(member: LlmPoolMember) {
  usageMember.value = member
  usageVisible.value = true
  usageLoading.value = true
  try {
    usageRows.value = await getPoolMemberUsage(member.id)
  } catch {
    ElMessage.error('加载用量明细失败')
    usageRows.value = []
  } finally {
    usageLoading.value = false
  }
}

/** 单日 token 合计 */
function dailyTokens(row: LlmPoolDailyUsage): number {
  return row.promptTokens + row.completionTokens + row.reasoningTokens
}

onMounted(() => {
  loadMembers()
})
</script>

<template>
  <el-card header="默认模型池">
    <p class="pool-desc">
      未自带 API Key 的翻译任务会走这里。引擎按「{{ windowDays }} 天内已消耗 token ÷ 配比」最小优先挑选成员，
      目的是把成本分散到多个账号上。成员遇到限流、鉴权失效、余额不足或模型名错误时会被自动冷却，
      期间的请求转由其他成员承担。
    </p>

    <el-alert
      v-if="!loading && members.length > 0 && !hasEnabledMember"
      type="error"
      show-icon
      :closable="false"
      class="pool-alert"
      title="当前没有启用中的成员"
      description="默认额度不可用，未自带 API Key 的上传会被直接拒绝。请启用至少一个成员。"
    />
    <el-alert
      v-else-if="!loading && members.length === 0"
      type="warning"
      show-icon
      :closable="false"
      class="pool-alert"
      title="模型池为空"
      description="默认额度不可用，未自带 API Key 的上传会被直接拒绝。请添加至少一个成员。"
    />
    <el-alert
      v-else-if="!loading && !runtimeAvailable"
      type="warning"
      show-icon
      :closable="false"
      class="pool-alert"
      title="拿不到引擎的实时状态"
      description="翻译引擎当前不可达，下方只展示配置与历史统计，冷却状态显示为「状态未知」。"
    />

    <div class="toolbar">
      <el-button v-if="props.isStarborn" type="primary" :icon="Plus" @click="openCreateDialog">添加成员</el-button>
      <el-button :icon="Refresh" :loading="loading" @click="loadMembers">刷新</el-button>
    </div>

    <el-table :data="members" v-loading="loading" empty-text="模型池为空" table-layout="auto">
      <el-table-column label="成员" min-width="140">
        <template #default="{ row }">
          <div class="member-name">{{ row.name }}</div>
          <div v-if="row.remark" class="member-remark">{{ row.remark }}</div>
        </template>
      </el-table-column>
      <el-table-column label="模型" prop="model" min-width="140" />
      <!-- 状态是排查问题最先看的信息，紧跟成员和模型放在第 3 列 -->
      <el-table-column label="状态" min-width="150">
        <template #default="{ row }">
          <el-tag :type="statusOf(row).type" size="small">{{ statusOf(row).text }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="API 地址" min-width="180">
        <template #default="{ row }">
          <span class="mono">{{ row.baseUrl }}</span>
        </template>
      </el-table-column>
      <el-table-column label="API Key" min-width="140">
        <template #default="{ row }">
          <span class="mono">{{ row.maskedApiKey }}</span>
        </template>
      </el-table-column>
      <el-table-column label="配比" width="70" align="center">
        <template #default="{ row }">{{ row.weight }}</template>
      </el-table-column>
      <el-table-column :label="`近 ${windowDays} 天 token`" min-width="140" align="right">
        <template #default="{ row }">
          <el-button text type="primary" size="small" @click="openUsage(row)">
            {{ formatNumber(row.stat?.windowTokens) }}
          </el-button>
          <div class="sub-metric">占比 {{ sharePercent(row) }}</div>
        </template>
      </el-table-column>
      <el-table-column :label="`近 ${windowDays} 天请求`" min-width="120" align="right">
        <template #default="{ row }">
          <div>{{ formatNumber(row.stat?.windowRequests) }}</div>
          <div class="sub-metric" :class="{ danger: isFailureRateHigh(row) }">
            失败 {{ failureRate(row) }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="最近失败" min-width="180">
        <template #default="{ row }">
          <el-tooltip v-if="lastErrorText(row) !== '-'" :content="lastErrorText(row)" placement="top">
            <span class="last-error">{{ lastErrorText(row) }}</span>
          </el-tooltip>
          <span v-else>-</span>
          <div v-if="row.stat?.lastFailureAt" class="sub-metric">{{ formatTime(row.stat.lastFailureAt) }}</div>
        </template>
      </el-table-column>
      <el-table-column v-if="props.isStarborn" label="操作" min-width="230" fixed="right">
        <template #default="{ row }">
          <div class="row-actions">
            <el-button size="small" :loading="testingId === row.id" @click="handleTest(row)">验证</el-button>
            <el-button size="small" @click="openEditDialog(row)">编辑</el-button>
            <el-button
              size="small"
              :type="row.enabled ? 'warning' : 'success'"
              @click="handleToggleEnabled(row, !row.enabled)"
            >
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button size="small" type="danger" @click="handleDelete(row)">删除</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog
    v-model="dialogVisible"
    :title="editingId === null ? '添加池成员' : '编辑池成员'"
    width="560px"
  >
    <el-form label-width="100px">
      <el-form-item label="成员名">
        <el-input v-model="form.name" placeholder="用于日志与本页定位，如 deepseek-main" />
      </el-form-item>
      <el-form-item label="API 地址">
        <el-input v-model="form.baseUrl" placeholder="只填到 /v1 这一层，如 https://api.deepseek.com" />
      </el-form-item>
      <el-form-item label="API Key">
        <el-input
          v-model="form.apiKey"
          type="password"
          show-password
          :placeholder="editingId === null ? '必填' : '留空表示不修改'"
        />
      </el-form-item>
      <el-form-item label="模型名称">
        <el-input v-model="form.model" placeholder="如 deepseek-v4-flash" />
      </el-form-item>
      <el-form-item label="成本配比">
        <el-input-number v-model="form.weight" :min="1" :max="100" />
        <span class="form-hint">值越大承担越多请求，按「用量 ÷ 配比」排序</span>
      </el-form-item>
      <el-form-item label="启用">
        <el-switch v-model="form.enabled" />
      </el-form-item>
      <el-form-item label="备注">
        <el-input v-model="form.remark" placeholder="如账号归属、额度上限" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确定</el-button>
    </template>
  </el-dialog>

  <el-drawer v-model="usageVisible" :title="`${usageMember?.name ?? ''} 每日用量`" size="520px">
    <el-table :data="usageRows" v-loading="usageLoading" empty-text="暂无用量记录" table-layout="auto">
      <el-table-column label="日期" prop="statDate" width="110" />
      <el-table-column label="请求" width="80" align="right">
        <template #default="{ row }">{{ formatNumber(row.requests) }}</template>
      </el-table-column>
      <el-table-column label="失败" width="80" align="right">
        <template #default="{ row }">{{ formatNumber(row.failures) }}</template>
      </el-table-column>
      <el-table-column label="token" align="right">
        <template #default="{ row }">{{ formatNumber(dailyTokens(row)) }}</template>
      </el-table-column>
    </el-table>
    <p v-if="usageMember?.stat" class="usage-total">
      累计：{{ formatNumber(usageMember.stat.totalRequests) }} 次请求，
      {{ formatNumber(usageMember.stat.totalTokens) }} token，
      失败 {{ formatNumber(usageMember.stat.totalFailures) }} 次
    </p>
  </el-drawer>
</template>

<style scoped>
.pool-desc { margin: 0 0 16px; font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.6; }
.pool-alert { margin-bottom: 16px; }
.toolbar { display: flex; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.member-name { font-weight: 500; }
.member-remark { font-size: 12px; color: var(--el-text-color-secondary); margin-top: 2px; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; word-break: break-all; }
.sub-metric { font-size: 12px; color: var(--el-text-color-secondary); }
.sub-metric.danger { color: var(--el-color-danger); }
.last-error {
  display: inline-block;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
  font-size: 12px;
}
/* 操作列宽度不够时按钮会换行，用 flex 保证每行都从左侧起排，不出现居中错位 */
.row-actions { display: flex; flex-wrap: wrap; justify-content: flex-start; gap: 8px; }
.row-actions :deep(.el-button + .el-button) { margin-left: 0; }
.form-hint { margin-left: 12px; font-size: 12px; color: var(--el-text-color-secondary); }
.usage-total { margin-top: 16px; font-size: 13px; color: var(--el-text-color-secondary); line-height: 1.6; }
</style>
