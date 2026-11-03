// 退出登录：账户菜单和设置页的账户卡片共用
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { logout } from '@/api/auth'
import { ApiError } from '@/api/http'
import { clearCurrentUser } from './session'

/** 返回一个函数：调用后端退出，清掉当前用户，回到登录页。失败时提示，留在原页面 */
export function useLogout() {
  const router = useRouter()
  return async function logoutAndLeave() {
    try {
      await logout()
    } catch (e) {
      ElMessage.error(e instanceof ApiError ? e.message : '退出登录失败，请重试')
      return
    }
    clearCurrentUser()
    await router.replace('/login')
  }
}
