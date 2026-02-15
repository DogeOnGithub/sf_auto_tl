import api from './api'
import type { PromptItem, PromptDetail } from '@/types'

/** 创建 Prompt 模板 */
export function createPrompt(data: { name: string; content: string }): Promise<PromptItem> {
  return api.post<PromptItem>('/api/prompts', data).then((res) => res.data)
}

/** 查询 Prompt 模板列表 */
export function listPrompts(): Promise<PromptItem[]> {
  return api.get<PromptItem[]>('/api/prompts').then((res) => res.data)
}

/** 查询 Prompt 模板详情 */
export function getPromptDetail(id: number): Promise<PromptDetail> {
  return api.get<PromptDetail>(`/api/prompts/${id}`).then((res) => res.data)
}

/** 更新 Prompt 模板 */
export function updatePrompt(id: number, data: { name: string; content: string }): Promise<PromptItem> {
  return api.put<PromptItem>(`/api/prompts/${id}`, data).then((res) => res.data)
}

/** 删除 Prompt 模板 */
export function deletePrompt(id: number): Promise<void> {
  return api.delete(`/api/prompts/${id}`).then(() => {})
}
