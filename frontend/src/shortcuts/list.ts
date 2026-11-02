// 全部快捷键，按 ? 弹出的列表和设置页的列表都从这里读（ui.md 第 6 节）
import { ref } from 'vue'
import { modKeyLabel } from './useShortcut'

export interface ShortcutEntry {
  /** 同时按下的键，每一项显示为一个 kbd，中间用 + 连接 */
  keys: string[]
  label: string
}

export const shortcutList: ShortcutEntry[] = [
  { keys: ['Enter'], label: '今天页开始学习、评分后进入下一个' },
  { keys: [modKeyLabel, 'Enter'], label: '发送回答' },
  { keys: ['1～4'], label: '评分' },
  { keys: ['Esc'], label: '从接着问返回评分页' },
  { keys: [modKeyLabel, 'K'], label: '搜索知识点' },
  { keys: ['?'], label: '查看全部快捷键' },
]

/** 快捷键列表弹窗是否打开：按 ? 或点账户菜单中的“快捷键”打开 */
export const shortcutHelpOpen = ref(false)
