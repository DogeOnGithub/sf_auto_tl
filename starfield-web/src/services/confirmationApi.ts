import api from './api'
import type { ConfirmationPageResponse, ConfirmationRecord } from '@/types'

/** 分页查询确认记录 */
export function listConfirmations(
  taskId: string,
  page: number,
  size: number,
  status?: string,
  keyword?: string,
): Promise<ConfirmationPageResponse> {
  return api
    .get<ConfirmationPageResponse>(`/api/translation-confirmation/${taskId}`, {
      params: { page, size, status: status || undefined, keyword: keyword || undefined },
    })
    .then((res) => res.data)
}

/** 编辑译文 */
export function updateTargetText(id: number, targetText: string): Promise<ConfirmationRecord> {
  return api
    .put<ConfirmationRecord>(`/api/translation-confirmation/${id}`, { targetText })
    .then((res) => res.data)
}

/** 逐条确认 */
export function confirmSingle(taskId: string, id: number): Promise<void> {
  return api.post(`/api/translation-confirmation/${taskId}/confirm`, { id }).then(() => {})
}

/** 批量确认 */
export function batchConfirm(taskId: string, ids: number[]): Promise<void> {
  return api.post(`/api/translation-confirmation/${taskId}/batch-confirm`, { ids }).then(() => {})
}

/** 全部确认 */
export function confirmAll(taskId: string): Promise<void> {
  return api.post(`/api/translation-confirmation/${taskId}/confirm-all`).then(() => {})
}

/** 触发文件生成 */
export function generateFile(taskId: string): Promise<void> {
  return api.post(`/api/translation-confirmation/${taskId}/generate`).then(() => {})
}
