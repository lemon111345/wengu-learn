import { get, post } from './http'

/** 登录的用户，对应后端的 LoginUser */
export interface LoginUser {
  id: number
  username: string
}

export interface RegisterRequest {
  username: string
  password: string
  /** 浏览器的 IANA 时区，静默提交 */
  timezone?: string
}

export interface LoginRequest {
  username: string
  password: string
}

/** 注册是否开放 */
export function fetchRegistration() {
  return get<{ open: boolean }>('/api/auth/registration')
}

/** 注册后后端直接登录 */
export function register(body: RegisterRequest) {
  return post<LoginUser>('/api/auth/register', body)
}

export function login(body: LoginRequest) {
  return post<LoginUser>('/api/auth/login', body, { skipAuthRedirect: true })
}

export function logout() {
  return post<void>('/api/auth/logout')
}

/** 当前登录的用户；没有登录时抛出 status 为 401 的 ApiError */
export function fetchMe() {
  return get<LoginUser>('/api/auth/me', { skipAuthRedirect: true })
}
