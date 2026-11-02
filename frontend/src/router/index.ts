import { createRouter, createWebHistory } from 'vue-router'
import { setUnauthorizedHandler } from '@/api/http'
import { clearCurrentUser, loadCurrentUser } from '@/auth/session'
import AppLayout from '@/layouts/AppLayout.vue'
import StudyLayout from '@/layouts/StudyLayout.vue'

// 两个页面共用一个组件，用 props 区分
const LegalView = () => import('@/views/LegalView.vue')

declare module 'vue-router' {
  interface RouteMeta {
    /** 只给没登录的人看（注册、登录）；已登录时直接进入应用 */
    guest?: boolean
    /** 谁都能看（用户协议、隐私政策） */
    public?: boolean
  }
}

// 没有标 guest 或 public 的页面都要求登录
const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: () => import('@/views/auth/LoginView.vue'), meta: { guest: true } },
    { path: '/register', component: () => import('@/views/auth/RegisterView.vue'), meta: { guest: true } },
    { path: '/terms', component: LegalView, props: { kind: 'terms' }, meta: { public: true } },
    { path: '/privacy', component: LegalView, props: { kind: 'privacy' }, meta: { public: true } },
    {
      // 应用框架：左侧导航 + 内容区（ui.md 4.1）
      path: '/',
      component: AppLayout,
      children: [
        { path: '', component: () => import('@/views/today/TodayView.vue') },
        { path: 'library', component: () => import('@/views/LibraryView.vue') },
        { path: 'import', component: () => import('@/views/import/ImportPage.vue') },
        // 草稿：抽取进度、确认知识点、已确认的结果，按状态显示（决策 028）
        { path: 'drafts/:id', component: () => import('@/views/import/DraftPage.vue'), props: true },
        { path: 'settings', component: () => import('@/views/SettingsView.vue') },
      ],
    },
    {
      // 学习模式：隐藏侧栏，内容居中一栏（ui.md 4.2）。入口按知识点，拿到会话后换成按会话 id 的地址（决策 027）
      path: '/study',
      component: StudyLayout,
      children: [{ path: ':kpId', component: () => import('@/views/study/StudyEntry.vue'), props: true }],
    },
    {
      path: '/session',
      component: StudyLayout,
      children: [
        // 学习，学完后同一页切换成收尾与评分
        { path: ':id', component: () => import('@/views/study/SessionPage.vue'), props: true },
        // 接着问，id 是原来的学习会话
        { path: ':id/follow-up', component: () => import('@/views/study/FollowUpPage.vue'), props: true },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
})

router.beforeEach(async (to) => {
  if (to.meta.public) {
    return true
  }
  // 连不上后端时当作没登录：停在登录页，提交时会显示原因
  const user = await loadCurrentUser().catch(() => null)
  if (to.meta.guest) {
    return user ? safeRedirect(to.query.redirect) : true
  }
  if (user) {
    return true
  }
  return to.fullPath === '/' ? '/login' : { path: '/login', query: { redirect: to.fullPath } }
})

// 使用中 Session 过期（如后端重启）：任何接口返回 401 都回到登录页，登录后回到当前页面
setUnauthorizedHandler(() => {
  clearCurrentUser()
  const current = router.currentRoute.value
  if (!current.meta.guest && !current.meta.public) {
    void router.replace({ path: '/login', query: { redirect: current.fullPath } })
  }
})

/** 登录后跳回的地址只接受站内路径，防止链接被构造成登录后跳到别的网站 */
export function safeRedirect(value: unknown): string {
  if (typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') && !value.startsWith('/\\')) {
    return value
  }
  return '/'
}

export default router
