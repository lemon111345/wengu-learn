/**
 * 流式回复的客户端（决策 025）：用 fetch 发 POST，自己读响应流，按 SSE 的格式拆出事件。
 * 不用浏览器的 EventSource：它只能发 GET，带不上回答内容和 CSRF 令牌。
 *
 * 后端在开始推送之前就做完校验，不通过时返回统一格式的 JSON 错误（如 409），这里和普通接口一样抛出 ApiError。
 */
import { ApiError, csrfHeaders, errorOf } from './http'
import type { Marker } from './sessions'

export interface StreamHandlers {
  /** 一段文字，依次拼接显示；标记已经由后端去掉 */
  onDelta: (text: string) => void
  /** 回复生成完并已存库 */
  onDone: (done: { messageId: number; marker: Marker | null; ended: boolean }) => void
  /** 模型出错，message 可以直接显示；学生的消息已经存下，重试会重新生成这条回复 */
  onError: (message: string) => void
}

/**
 * 发起一次流式请求，读到流结束为止。
 *
 * @throws ApiError 推送之前的校验不通过（status 为 4xx），或者连不上服务器（status 为 0）
 * @throws StreamInterrupted 推送到一半连接断了（网络断开、后端重启），已经收到的内容由调用方保留
 */
export async function postStream(url: string, body: unknown, handlers: StreamHandlers, signal?: AbortSignal) {
  let response: Response
  try {
    response = await fetch(url, {
      method: 'POST',
      headers: { ...csrfHeaders(), ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      body: body === undefined ? undefined : JSON.stringify(body),
      signal,
    })
  } catch (e) {
    if (signal?.aborted) {
      return
    }
    throw new ApiError(0, 'NETWORK_ERROR', '连不上服务器，请检查网络后重试')
  }
  if (!response.ok || !response.body) {
    throw await errorOf(response)
  }

  const reader = response.body.pipeThrough(new TextDecoderStream()).getReader()
  let buffer = ''
  let finished = false
  try {
    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      buffer += value
      // 事件之间用空行分隔；最后一段可能不完整，留到下一次
      const parts = buffer.split(/\r?\n\r?\n/)
      buffer = parts.pop() ?? ''
      for (const part of parts) {
        finished = dispatch(part, handlers) || finished
      }
    }
  } catch {
    if (signal?.aborted) {
      return
    }
    throw new StreamInterrupted()
  }
  // 没收到 done 或 error 就结束了：连接被中途切断
  if (!finished && !signal?.aborted) {
    throw new StreamInterrupted()
  }
}

/** 推送到一半连接断了 */
export class StreamInterrupted extends Error {
  constructor() {
    super('连接中断')
    this.name = 'StreamInterrupted'
  }
}

/** 处理一个事件，返回它是不是结束事件（done 或 error） */
function dispatch(raw: string, handlers: StreamHandlers): boolean {
  let event = 'message'
  const data: string[] = []
  for (const line of raw.split(/\r?\n/)) {
    if (line.startsWith('event:')) {
      event = line.slice('event:'.length).trim()
    } else if (line.startsWith('data:')) {
      // 规范规定冒号后面的一个空格不算内容
      data.push(line.slice('data:'.length).replace(/^ /, ''))
    }
  }
  if (data.length === 0) {
    return false
  }
  const payload = JSON.parse(data.join('\n'))
  switch (event) {
    case 'delta':
      handlers.onDelta(payload.text)
      return false
    case 'done':
      handlers.onDone(payload)
      return true
    case 'error':
      handlers.onError(payload.message)
      return true
    default:
      return false
  }
}
