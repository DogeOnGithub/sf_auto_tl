import api from './api'
import type { FileUploadResponse } from '@/types'

/** 上传 ESM 文件，支持上传进度回调、关联 creation 版本、Prompt 参数和自定义 LLM 配置 */
export function uploadFile(
  file: File,
  onProgress?: (percent: number) => void,
  creationVersionId?: number,
  promptId?: number,
  newPromptName?: string,
  newPromptContent?: string,
  confirmationMode?: string,
  llmBaseUrl?: string,
  llmApiKey?: string,
  llmModel?: string,
): Promise<FileUploadResponse> {
  var formData = new FormData()
  formData.append('file', file)
  if (creationVersionId) {
    formData.append('creationVersionId', String(creationVersionId))
  }
  if (promptId) {
    formData.append('promptId', String(promptId))
  }
  if (newPromptName) {
    formData.append('newPromptName', newPromptName)
  }
  if (newPromptContent) {
    formData.append('newPromptContent', newPromptContent)
  }
  if (confirmationMode) {
    formData.append('confirmationMode', confirmationMode)
  }
  if (llmBaseUrl) {
    formData.append('llmBaseUrl', llmBaseUrl)
  }
  if (llmApiKey) {
    formData.append('llmApiKey', llmApiKey)
  }
  if (llmModel) {
    formData.append('llmModel', llmModel)
  }
  return api
    .post<FileUploadResponse>('/api/files/upload', formData, {
      timeout: 1800000,
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded * 100) / e.total))
        }
      },
    })
    .then((res) => res.data)
}
