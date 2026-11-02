// 学习模式顶栏要显示的东西，由页面告诉布局：本次的分钟数、什么时候重新取当天进度。
// 布局和页面是父子路由，没有直接的 props 可传，用模块级的 reactive 共享，和 auth/session.ts 一样
import { reactive } from 'vue'

export const studyHeader = reactive({
  /** 本次的有效分钟数；为 null 时不显示 */
  minutes: null as number | null,
  /** 加一表示进度变了（如评完分），布局重新取今天的计划 */
  progressVersion: 0,
})

/** 页面拿到会话后调用，秒数来自后端的有效时长 */
export function setStudyMinutes(activeSeconds: number | null) {
  studyHeader.minutes = activeSeconds === null ? null : Math.floor(activeSeconds / 60)
}

export function refreshStudyProgress() {
  studyHeader.progressVersion++
}
