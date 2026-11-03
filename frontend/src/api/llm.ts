// AI 模型状态（#27，决策 032）：头像上的红点和设置页的 AI 模型卡片（#52）用它
import { get } from './http'

export interface LlmLastCall {
  /** 模型是否有回应 */
  ok: boolean
  /** 失败时的返回码，如 LLM_AUTH_FAILED */
  code: string | null
  /** 失败时给用户看的提示 */
  message: string | null
  /** 调用结束的时间（UTC） */
  at: string
}

export interface LlmStatus {
  model: string
  /** 后端重启后还没有调用过时为 null */
  lastCall: LlmLastCall | null
}

/** 只读后端记下的最近一次结果，不会真实调用模型 */
export function fetchLlmStatus() {
  return get<LlmStatus>('/api/llm/status')
}
