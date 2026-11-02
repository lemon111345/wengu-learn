// 学习模式的布局和页面共用的状态：页面告诉布局本次的分钟数、什么时候重新取当天进度；
// 布局取到的今天的计划也放在这里，页面用它显示“新学 / 复习”和类型标签、找下一个知识点。
// 布局和页面是父子路由，没有直接的 props 可传，用模块级的 reactive 共享，和 auth/session.ts 一样
import { reactive } from 'vue'
import type { TodayPlan } from '@/api/plan'

export const studyHeader = reactive({
  /** 本次的有效分钟数；为 null 时不显示 */
  minutes: null as number | null,
  /** 加一表示进度变了（如评完分），布局重新取今天的计划 */
  progressVersion: 0,
  /** 布局取到的今天的计划；取不到时为 null */
  plan: null as TodayPlan | null,
})

/** 页面拿到会话后调用，秒数来自后端的有效时长 */
export function setStudyMinutes(activeSeconds: number | null) {
  studyHeader.minutes = activeSeconds === null ? null : Math.floor(activeSeconds / 60)
}

export function refreshStudyProgress() {
  studyHeader.progressVersion++
}
