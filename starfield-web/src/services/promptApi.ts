import api from './api'
import type { PromptResponse } from '@/types'

/** 获取当前 Prompt */
export function getCurrentPrompt(): Promise<PromptResponse> {
  return api.get<PromptResponse>('/api/prompts/current').then((res) => res.data)
}

/** 保存自定义 Prompt */
export function savePrompt(content: string): Promise<PromptResponse> {
  return api
    .put<PromptResponse>('/api/prompts', { content })
    .then((res) => res.data)
}

/** 恢复默认 Prompt */
export function resetPrompt(): Promise<PromptResponse> {
  return api.delete<PromptResponse>('/api/prompts').then((res) => res.data)
}
