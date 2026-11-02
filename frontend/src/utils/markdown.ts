/**
 * 老师回复的格式渲染：只认对话中常见的几种 Markdown（决策 027），不引入第三方库。
 * - 代码块（```），流式输出时还没闭合的代码块也按代码块显示
 * - 行内代码（`x`）、加粗（**x**）
 * - 段落、有序列表（1. ）、无序列表（- 或 * ）、标题（# ，显示成加粗的一行）
 * - 表格（表头 + |---| 分隔线 + 表格行）
 *
 * 先把全部内容按 HTML 转义，再识别格式，模型输出的任何标签都只会显示成文字，不会被浏览器执行。
 * 其他写法（引用、链接、图片等）原样显示。
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
const TABLE_ROW = /^\s*\|.*\|\s*$/
const TABLE_SEPARATOR = /^\s*\|?(\s*:?-{2,}:?\s*\|)+\s*(:?-{2,}:?\s*)?\|?\s*$/

/** 表格的一行拆成单元格：去掉首尾的竖线 */
function cells(row: string): string[] {
  return row.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map((c) => c.trim())
}

/** 第一行是表头；外面包一层，表格宽时在自己的容器内横向滚动 */
function table(rows: string[]): string {
  const [head, ...body] = rows
  const th = cells(head).map((c) => `<th>${inline(c)}</th>`).join('')
  const tr = body.map((r) => `<tr>${cells(r).map((c) => `<td>${inline(c)}</td>`).join('')}</tr>`).join('')
  return `<div class="table-wrap"><table><thead><tr>${th}</tr></thead><tbody>${tr}</tbody></table></div>`
}

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

  const lines = text.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    // 表格：一行表头、一行分隔线（|---|---|），之后连续的表格行
    if (TABLE_ROW.test(line) && i + 1 < lines.length && TABLE_SEPARATOR.test(lines[i + 1])) {
      flushParagraph()
      flushList()
      const rows = [line]
      for (i += 2; i < lines.length && TABLE_ROW.test(lines[i]); i++) {
        rows.push(lines[i])
      }
      i--
      out.push(table(rows))
      continue
    }
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
