/**
 * 当前登录的用户。全应用只有这一份，用模块级的 ref 保存，不需要 Pinia。
 * 登录状态本身在后端的 Session 中（决策 022），这里只是缓存，刷新页面后重新向后端查询。
 */
import { readonly, ref } from 'vue'
import { fetchMe, type LoginUser } from '@/api/auth'
import { ApiError } from '@/api/http'

const user = ref<LoginUser | null>(null)
// 正在进行或已经完成的查询；为 null 表示还没问过后端
let loading: Promise<LoginUser | null> | null = null

/** 页面上读这个；修改只能通过下面的函数 */
export const currentUser = readonly(user)

/**
 * 第一次调用时向后端查询当前用户，之后直接用缓存。没有登录返回 null。
 * 连不上后端等其他错误照常抛出，并且不缓存，下次调用重新查询。
 */
export function loadCurrentUser(): Promise<LoginUser | null> {
  loading ??= fetchMe()
    .then((me) => (user.value = me))
    .catch((e: unknown) => {
      if (e instanceof ApiError && e.status === 401) {
        return (user.value = null)
      }
      loading = null
      throw e
    })
  return loading
}

/** 注册或登录成功后调用 */
export function setCurrentUser(me: LoginUser) {
  user.value = me
  loading = Promise.resolve(me)
}

/** 退出登录或收到 401 后调用 */
export function clearCurrentUser() {
  user.value = null
  loading = Promise.resolve(null)
}
