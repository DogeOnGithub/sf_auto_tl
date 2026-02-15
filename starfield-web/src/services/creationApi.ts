import api from './api'
import type { Creation, CreationPageResponse, TaskResponse } from '@/types'

/** 创建 Mod 作品（或为同名 mod 添加新版本） */
export function createCreation(formData: FormData): Promise<Creation> {
  return api.post<Creation>('/api/creations', formData, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((res) => res.data)
}

/** 分页查询作品列表 */
export function getCreations(page = 1, size = 12, keyword?: string): Promise<CreationPageResponse> {
  return api.get<CreationPageResponse>('/api/creations', {
    params: { page, size, keyword },
  }).then((res) => res.data)
}

/** 查询作品详情 */
export function getCreation(id: number): Promise<Creation> {
  return api.get<Creation>(`/api/creations/${id}`).then((res) => res.data)
}

/** 更新作品基本信息 */
export function updateCreation(id: number, data: Record<string, unknown>): Promise<Creation> {
  return api.put<Creation>(`/api/creations/${id}`, data).then((res) => res.data)
}

/** 删除作品 */
export function deleteCreation(id: number): Promise<void> {
  return api.delete(`/api/creations/${id}`).then(() => {})
}

/** 删除指定版本 */
export function deleteCreationVersion(versionId: number): Promise<void> {
  return api.delete(`/api/creations/versions/${versionId}`).then(() => {})
}

/** 查询作品关联的翻译任务 */
export function getCreationTasks(creationId: number): Promise<TaskResponse[]> {
  return api.get<TaskResponse[]>(`/api/creations/${creationId}/tasks`).then((res) => res.data)
}

/** 上传汉化补丁文件 */
export function uploadPatch(versionId: number, file: File): Promise<Creation> {
  var fd = new FormData()
  fd.append('file', file)
  return api.post<Creation>(`/api/creations/versions/${versionId}/patch`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((res) => res.data)
}

/** 上传/替换 Mod 文件 */
export function uploadFile(versionId: number, file: File): Promise<Creation> {
  var fd = new FormData()
  fd.append('file', file)
  return api.post<Creation>(`/api/creations/versions/${versionId}/file`, fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
  }).then((res) => res.data)
}

