import api from './api'
import type { CachePageResponse, CacheEntry } from '@/types'

/** 分页查询翻译缓存 */
export function getCacheEntries(page: number, size: number, keyword?: string): Promise<CachePageResponse> {
  return api
    .get<CachePageResponse>('/api/translation-cache/list', {
      params: { page, size, ...(keyword ? { keyword } : {}) },
    })
    .then((res) => res.data)
}

/** 更新缓存译文 */
export function updateCacheEntry(id: number, targetText: string): Promise<CacheEntry> {
  return api
    .put<CacheEntry>(`/api/translation-cache/${id}`, { targetText })
    .then((res) => res.data)
}

/** 删除缓存记录 */
export function deleteCacheEntry(id: number): Promise<void> {
  return api.delete(`/api/translation-cache/${id}`).then(() => {})
}

/** 批量删除缓存记录 */
export function batchDeleteCacheEntries(ids: number[]): Promise<void> {
  return api.delete('/api/translation-cache/batch', { data: ids }).then(() => {})
}
