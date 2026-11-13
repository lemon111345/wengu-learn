// 面试参考答法的读取（#111）：只在展开时才取，取过的记在这里，同一个会话再展开不重复请求。
// 学习结束后收尾不会再变，所以不用过期；失败的不记，下次展开重试
import { fetchSession } from '@/api/sessions'

const cache = new Map<number, string | null>()

export async function loadReferenceAnswer(sessionId: number): Promise<string | null> {
  if (cache.has(sessionId)) {
    return cache.get(sessionId) ?? null
  }
  const answer = (await fetchSession(sessionId)).referenceAnswer
  cache.set(sessionId, answer)
  return answer
}
