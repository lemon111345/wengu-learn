/**
 * 老师回复的格式渲染：只认对话中常见的几种 Markdown（决策 027），不引入第三方库。
 * - 代码块（```），流式输出时还没闭合的代码块也按代码块显示
 * - 行内代码（`x`）、加粗（**x**）
 * - 段落、有序列表（1. ）、无序列表（- 或 * ）、标题（# ，显示成加粗的一行）
 *
 * 先把全部内容按 HTML 转义，再识别格式，模型输出的任何标签都只会显示成文字，不会被浏览器执行。
 * 表格等其他写法原样显示。
 */

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }

function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ESCAPES[c])
}

/** 行内格式：先按反引号切开，代码里的内容不再识别加粗 */
function inline(text: string): string {
  return text
    .split(/(`[^`\n]+`)/)
    .map((part) =>
      part.startsWith('`') && part.endsWith('`') && part.length > 1
        ? `<code>${escapeHtml(part.slice(1, -1))}</code>`
        : escapeHtml(part).replace(/\*\*([^*\n]+)\*\*/g, '<strong>$1</strong>'),
    )
    .join('')
}

const UNORDERED = /^\s*[-*]\s+(.*)$/
const ORDERED = /^\s*\d+[.)]\s+(.*)$/
const HEADING = /^#{1,6}\s+(.*)$/

/** 一段不含代码块的文字：按行归成段落、列表、标题 */
function blocks(text: string): string {
  const out: string[] = []
  let paragraph: string[] = []
  let list: { tag: 'ul' | 'ol'; items: string[] } | null = null

  const flushParagraph = () => {
    if (paragraph.length > 0) {
      out.push(`<p>${paragraph.map(inline).join('<br>')}</p>`)
      paragraph = []
    }
  }
  const flushList = () => {
    if (list) {
      out.push(`<${list.tag}>${list.items.map((i) => `<li>${inline(i)}</li>`).join('')}</${list.tag}>`)
      list = null
    }
  }

  for (const line of text.split('\n')) {
    const unordered = UNORDERED.exec(line)
    const ordered = unordered ? null : ORDERED.exec(line)
    const heading = HEADING.exec(line)
    if (unordered || ordered) {
      const tag = unordered ? 'ul' : 'ol'
      flushParagraph()
      if (list && list.tag !== tag) {
        flushList()
      }
      list ??= { tag, items: [] }
      list.items.push((unordered ?? ordered)![1])
    } else if (heading) {
      flushParagraph()
      flushList()
      out.push(`<p><strong>${inline(heading[1])}</strong></p>`)
    } else if (line.trim() === '') {
      flushParagraph()
      flushList()
    } else {
      flushList()
      paragraph.push(line)
    }
  }
  flushParagraph()
  flushList()
  return out.join('')
}

/** 把一条老师回复渲染成 HTML，结果可以直接用 v-html 显示 */
export function renderMessage(text: string): string {
  const source = text.replace(/\r\n/g, '\n')
  const out: string[] = []
  // 代码块：```语言名 换行 内容 ```；最后一个没闭合时一直到结尾（流式输出还没写完）
  const fence = /```[^\n`]*\n?([\s\S]*?)(```|$)/g
  let last = 0
  for (let m = fence.exec(source); m !== null; m = fence.exec(source)) {
    out.push(blocks(source.slice(last, m.index)))
    out.push(`<pre><code>${escapeHtml(m[1].replace(/\n$/, ''))}</code></pre>`)
    last = fence.lastIndex
  }
  out.push(blocks(source.slice(last)))
  return out.join('')
}
