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
  progress: { translated: number; total: number }
  creation: TaskCreationInfo | null
  prompt: { id: number; name: string } | null
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
