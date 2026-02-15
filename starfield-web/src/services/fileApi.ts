import api from './api'
import type { FileUploadResponse } from '@/types'

/** 上传 ESM 文件，支持上传进度回调、关联 creation 版本和 Prompt 参数 */
export function uploadFile(
  file: File,
  onProgress?: (percent: number) => void,
  creationVersionId?: number,
  promptId?: number,
  newPromptName?: string,
  newPromptContent?: string,
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
  return api
    .post<FileUploadResponse>('/api/files/upload', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
      onUploadProgress: (e) => {
        if (onProgress && e.total) {
          onProgress(Math.round((e.loaded * 100) / e.total))
        }
      },
    })
    .then((res) => res.data)
}
