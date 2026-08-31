import api from './api'
import type {
  LlmPoolDailyUsage,
  LlmPoolMember,
  LlmPoolMemberRequest,
  LlmPoolTestResult,
} from '@/types'

const BASE = '/api/llm-pool/members'

/** 查询池成员列表（含用量统计与引擎侧实时健康状态） */
export function getPoolMembers(): Promise<LlmPoolMember[]> {
  return api.get<LlmPoolMember[]>(BASE).then((res) => res.data)
}

/** 新增池成员 */
export function createPoolMember(payload: LlmPoolMemberRequest): Promise<LlmPoolMember> {
  return api.post<LlmPoolMember>(BASE, payload).then((res) => res.data)
}

/** 修改池成员，payload.apiKey 留空表示沿用原有 Key */
export function updatePoolMember(
  id: number,
  payload: LlmPoolMemberRequest,
): Promise<LlmPoolMember> {
  return api.put<LlmPoolMember>(`${BASE}/${id}`, payload).then((res) => res.data)
}

/** 删除池成员（同时清掉它的用量统计） */
export function deletePoolMember(id: number): Promise<void> {
  return api.delete(`${BASE}/${id}`).then(() => undefined)
}

/**
 * 验证池成员凭证连通性
 *
 * 由引擎打一次极小的补全请求，走的是和真实翻译完全相同的路径，
 * 因此能提前暴露 base_url 误填、模型名不存在这类配置错误。
 * 超时给到 60s：验证请求要真的打到对方服务，默认 30s 在慢供应商上容易误判。
 */
export function testPoolMember(id: number): Promise<LlmPoolTestResult> {
  return api
    .post<LlmPoolTestResult>(`${BASE}/${id}/test`, null, { timeout: 60000 })
    .then((res) => res.data)
}

/** 查询池成员每日用量 */
export function getPoolMemberUsage(id: number, days = 14): Promise<LlmPoolDailyUsage[]> {
  return api
    .get<LlmPoolDailyUsage[]>(`${BASE}/${id}/usage`, { params: { days } })
    .then((res) => res.data)
}
