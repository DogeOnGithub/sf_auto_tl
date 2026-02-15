import api from './api'
import type { DictionaryEntry, DictionaryEntriesResponse } from '@/types'

/** 查询词典词条（支持关键词搜索） */
export function getEntries(keyword?: string): Promise<DictionaryEntriesResponse> {
  return api
    .get<DictionaryEntriesResponse>('/api/dictionary/entries', {
      params: keyword ? { keyword } : undefined,
    })
    .then((res) => res.data)
}

/** 添加词条 */
export function createEntry(
  sourceText: string,
  targetText: string,
): Promise<DictionaryEntry> {
  return api
    .post<DictionaryEntry>('/api/dictionary/entries', { sourceText, targetText })
    .then((res) => res.data)
}

/** 更新词条 */
export function updateEntry(
  id: number,
  sourceText: string,
  targetText: string,
): Promise<DictionaryEntry> {
  return api
    .put<DictionaryEntry>(`/api/dictionary/entries/${id}`, { sourceText, targetText })
    .then((res) => res.data)
}

/** 删除词条 */
export function deleteEntry(id: number): Promise<void> {
  return api.delete(`/api/dictionary/entries/${id}`).then(() => undefined)
}
