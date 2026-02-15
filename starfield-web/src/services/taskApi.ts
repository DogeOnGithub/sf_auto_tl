import api from './api'
import type { DownloadResponse, TaskResponse } from '@/types'

/** 查询所有翻译任务列表 */
export function getTasks(): Promise<TaskResponse[]> {
  return api.get<TaskResponse[]>('/api/tasks').then((res) => res.data)
}

/** 查询翻译任务状态 */
export function getTask(taskId: string): Promise<TaskResponse> {
  return api.get<TaskResponse>(`/api/tasks/${taskId}`).then((res) => res.data)
}

/** 获取翻译结果下载信息（COS URL + 文件名） */
export function downloadFile(taskId: string): Promise<DownloadResponse> {
  return api
    .get<DownloadResponse>(`/api/tasks/${taskId}/download`)
    .then((res) => res.data)
}
