export interface TaskCreationInfo {
  creationId: number
  name: string
  translatedName: string
  versionId: number
  version: string
}

export interface TaskResponse {
  taskId: string
  fileName: string
  status: string
  confirmationMode: string
  sourceType: string
  progress: { translated: number; total: number }
  creation: TaskCreationInfo | null
  prompt: { id: number; name: string } | null
  llm: { baseUrl: string; model: string } | null
  errorMessage: string | null
  createdAt: string
  updatedAt: string
}

export interface FileUploadResponse {
  taskId: string
  fileName: string
}

export interface DownloadResponse {
  downloadUrl: string
  fileName: string
}

export interface PromptItem {
  id: number
  name: string
  content: string
  usageCount: number
  createdAt: string
  updatedAt: string
}

export interface TaskBriefInfo {
  taskId: string
  fileName: string
  status: string
  createdAt: string
}

export interface PromptDetail extends PromptItem {
  tasks: TaskBriefInfo[]
}

export interface DictionaryEntry {
  id: number
  sourceText: string
  targetText: string
}

export interface DictionaryEntriesResponse {
  entries: DictionaryEntry[]
}

export interface ErrorResponse {
  error: string
  message: string
}

export interface CreationVersion {
  id: number
  version: string
  filePath: string
  fileName: string
  fileShareLink: string
  patchFilePath: string
  patchFileName: string
  createdAt: string
}

export interface CreationImage {
  id: number
  url: string
  sortOrder: number
}

export interface CreationWarning {
  id: number
  content: string
  status: 'UNRESOLVED' | 'RESOLVED'
  createdAt: string
  updatedAt: string
}

export interface Creation {
  id: number
  name: string
  translatedName: string
  author: string
  ccLink: string
  nexusLink: string
  remark: string
  tags: string[]
  versions: CreationVersion[]
  images: CreationImage[]
  hasChinesePatch: boolean
  featured: boolean
  featuredAt: string | null
  bannerImageUrl: string | null
  warnings: CreationWarning[]
  createdAt: string
  updatedAt: string
}

export interface CreationPageResponse {
  records: Creation[]
  total: number
  current: number
  pages: number
}

export interface CacheEntry {
  id: number
  taskId: string
  recordType: string
  subrecordType: string
  sourceText: string
  targetText: string
  targetLang: string
  createdAt: string
  updatedAt: string
}

export interface CachePageResponse {
  records: CacheEntry[]
  total: number
  current: number
  pages: number
}

export interface TaskPageResponse {
  records: TaskResponse[]
  total: number
  current: number
  pages: number
}

export interface ConfirmationRecord {
  id: number
  taskId: string
  recordId: string
  recordType: string
  editorId: string
  sourceText: string
  targetText: string
  status: string
  createdAt: string
  updatedAt: string
}

export interface ConfirmationPageResponse {
  records: ConfirmationRecord[]
  total: number
  current: number
  pages: number
}

/** 池成员滚动窗口内的用量统计 */
export interface LlmPoolMemberStat {
  /** 统计窗口天数，与调度口径一致 */
  windowDays: number
  /** 窗口内请求数，含失败 */
  windowRequests: number
  /** 窗口内失败数 */
  windowFailures: number
  /** 窗口内消耗的总 token，调度排序的依据 */
  windowTokens: number
  totalRequests: number
  totalFailures: number
  totalTokens: number
  lastSuccessAt: string | null
  lastFailureAt: string | null
  /** 最近失败原因，已按错误类型归一 */
  lastFailureReason: string | null
}

/** 池成员在引擎进程内的实时健康状态，引擎不可达时整体为 null */
export interface LlmPoolMemberRuntime {
  /** 当前是否可被调度，冷却中为 false */
  available: boolean
  /** 冷却剩余秒数，未冷却为 0 */
  cooldownRemainingSeconds: number
  /** 最近失败归类：rate_limit / auth / quota / model_not_found / transient / bad_request */
  lastErrorKind: string | null
  lastErrorMessage: string | null
}

/** 默认 LLM 凭证池成员 */
export interface LlmPoolMember {
  id: number
  /** 成员名，日志与页面的定位标识 */
  name: string
  baseUrl: string
  /** 脱敏后的 Key，明文不出后端 */
  maskedApiKey: string
  model: string
  /** 成本分摊配比，值越大承担越多 */
  weight: number
  enabled: boolean
  remark: string | null
  stat: LlmPoolMemberStat | null
  runtime: LlmPoolMemberRuntime | null
  createdAt: string
  updatedAt: string
}

/** 池成员新增/修改入参，apiKey 留空表示沿用原值 */
export interface LlmPoolMemberRequest {
  name: string
  baseUrl: string
  apiKey?: string
  model: string
  weight: number
  enabled: boolean
  remark?: string
}

/** 池成员连通性验证结果 */
export interface LlmPoolTestResult {
  success: boolean
  message: string
  latencyMs: number
}

/** 池成员单日用量 */
export interface LlmPoolDailyUsage {
  statDate: string
  requests: number
  failures: number
  promptTokens: number
  completionTokens: number
  reasoningTokens: number
}
