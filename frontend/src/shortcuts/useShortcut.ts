/**
 * 快捷键（ui.md 第 6 节）。组件中调用 useShortcut，挂载时生效，卸载时自动移除。
 *
 * key 的写法：
 * - 单键：KeyboardEvent.key 的值，如 'Enter'、'1'、'?'、'Escape'
 * - 组合键：'Mod+Enter'、'Mod+k'，Mod 在 Windows 上是 Ctrl，在 Mac 上是 Cmd
 *
 * 单键快捷键在下面几种情况不生效，免得和打字、按钮冲突：
 * - 焦点在输入框、文本框、下拉框或可编辑区域中
 * - 焦点在弹窗中（弹窗外的页面快捷键）
 * - Enter 的焦点在按钮或链接上：这时 Enter 本来就会点击它
 */
import { onBeforeUnmount, onMounted } from 'vue'

export const isMac = /Mac|iPhone|iPad/.test(navigator.userAgent)

/** 显示用的 Mod 键名称 */
export const modKeyLabel = isMac ? 'Cmd' : 'Ctrl'

export interface ShortcutOptions {
  /** 焦点在输入框中时也生效，如接着问页面的 Esc */
  allowInInput?: boolean
}

export function useShortcut(key: string, handler: (event: KeyboardEvent) => void, options: ShortcutOptions = {}) {
  const withMod = key.startsWith('Mod+')
  const baseKey = (withMod ? key.slice('Mod+'.length) : key).toLowerCase()

  function onKeydown(event: KeyboardEvent) {
    // 输入法组字时的 Enter 是确认候选词，不是快捷键
    if (event.isComposing || event.key.toLowerCase() !== baseKey) {
      return
    }
    const modPressed = isMac ? event.metaKey : event.ctrlKey
    if (modPressed !== withMod || event.altKey) {
      return
    }
    if (!withMod && !options.allowInInput && isBlocked(event)) {
      return
    }
    event.preventDefault()
    handler(event)
  }

  onMounted(() => window.addEventListener('keydown', onKeydown))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
}

const TEXT_INPUT_EXCLUDED = new Set(['checkbox', 'radio', 'button', 'submit', 'reset', 'range', 'color', 'file'])

function isBlocked(event: KeyboardEvent): boolean {
  const target = event.target
  if (!(target instanceof HTMLElement)) {
    return false
  }
  if (target.isContentEditable || target instanceof HTMLTextAreaElement || target instanceof HTMLSelectElement) {
    return true
  }
  if (target instanceof HTMLInputElement && !TEXT_INPUT_EXCLUDED.has(target.type)) {
    return true
  }
  if (target.closest('[role="dialog"]')) {
    return true
  }
  return event.key === 'Enter' && target.closest('button, a, [role="button"]') !== null
}
