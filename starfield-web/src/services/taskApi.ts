import api from './api'
import type { TaskResponse } from '@/types'

/** 查询所有翻译任务列表 */
export function getTasks(): Promise<TaskResponse[]> {
  return api.get<TaskResponse[]>('/api/tasks').then((res) => res.data)
}

/** 查询翻译任务状态 */
export function getTask(taskId: string): Promise<TaskResponse> {
  return api.get<TaskResponse>(`/api/tasks/${taskId}`).then((res) => res.data)
}

/** 下载翻译后或原始 ESM 文件 */
export function downloadFile(
  taskId: string,
  type: 'translated' | 'original' = 'translated',
): Promise<Blob> {
  return api
    .get(`/api/tasks/${taskId}/download`, {
      params: { type },
      responseType: 'blob',
    })
    .then((res) => res.data as Blob)
}
