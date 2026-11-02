/**
 * 请求封装：所有接口都经过这里。
 * - 解析后端的统一返回格式 {code, message, data}（决策 001），失败时抛出 ApiError
 * - 非 GET 请求带上 CSRF 令牌（决策 022）
 * - 收到 401 时通知路由跳转登录页
 */

interface Result<T> {
  code: string
  message: string
  data: T
}

/** 接口失败。message 是给用户看的提示，可以直接显示。 */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message)
    this.name = 'ApiError'
  }
}

export interface RequestOptions {
  body?: unknown
  /** 不因 401 跳转登录页：登录接口的 401 是“用户名或密码错误”；查询当前用户时由调用方自己处理 */
  skipAuthRedirect?: boolean
}

// 401 的处理由路由注册进来。http 不直接引用路由，免得路由、页面、接口之间循环引用
let onUnauthorized = () => {}

export function setUnauthorizedHandler(handler: () => void) {
  onUnauthorized = handler
}

export async function request<T>(method: string, url: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = {}
  if (options.body !== undefined) {
    headers['Content-Type'] = 'application/json'
  }
  if (method !== 'GET') {
    // 令牌每次都从 Cookie 现读：后端可能更换令牌，缓存旧值会被拒
    const token = readCookie('XSRF-TOKEN')
    if (token) {
      headers['X-XSRF-TOKEN'] = token
    }
  }

  let response: Response
  try {
    response = await fetch(url, {
      method,
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    })
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', '连不上服务器，请检查网络后重试')
  }

  const result = await parseResult<T>(response)
  if (response.ok && result?.code === 'OK') {
    return result.data
  }
  if (response.status === 401 && !options.skipAuthRedirect) {
    onUnauthorized()
  }
  // 后端没启动时，开发代理返回的不是统一格式
  throw new ApiError(response.status, result?.code ?? 'UNKNOWN', result?.message ?? '服务暂时不可用，请稍后重试')
}

export function get<T>(url: string, options?: RequestOptions) {
  return request<T>('GET', url, options)
}

export function post<T>(url: string, body?: unknown, options?: RequestOptions) {
  return request<T>('POST', url, { ...options, body })
}

async function parseResult<T>(response: Response): Promise<Result<T> | null> {
  try {
    const body: unknown = await response.json()
    return body !== null && typeof body === 'object' && 'code' in body ? (body as Result<T>) : null
  } catch {
    return null
  }
}

function readCookie(name: string): string | undefined {
  const prefix = name + '='
  const item = document.cookie.split('; ').find((c) => c.startsWith(prefix))
  return item === undefined ? undefined : decodeURIComponent(item.slice(prefix.length))
}
