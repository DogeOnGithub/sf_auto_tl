import api from './api'
import type { DownloadResponse, TaskPageResponse, TaskResponse } from '@/types'

/** 查询所有翻译任务列表 */
export function getTasks(): Promise<TaskResponse[]> {
  return api.get<TaskResponse[]>('/api/tasks').then((res) => res.data)
}

/** 分页查询翻译任务列表 */
export function getTasksPaged(page: number, size: number): Promise<TaskPageResponse> {
  return api.get<TaskPageResponse>('/api/tasks/page', { params: { page, size } }).then((res) => res.data)
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

/** 手动清理翻译任务（删除 COS 文件并标记过期） */
export function expireTask(taskId: string): Promise<void> {
  return api.post(`/api/tasks/${taskId}/expire`).then(() => {})
}

/** 批量清理翻译任务 */
export function batchExpireTasks(taskIds: string[]): Promise<{ expiredCount: number }> {
  return api.post<{ expiredCount: number }>('/api/tasks/batch-expire', taskIds).then((res) => res.data)
}
