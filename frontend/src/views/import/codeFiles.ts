// 源码导入（#90，决策 050、ui.md 5.7）在浏览器里的规则：哪些文件显示、哪些默认勾选、字符数怎么数、文件树怎么建。
// 都是纯函数，不碰页面。字符数的算法必须和后端 SourceService.importCode 一致，否则会出现“前端没超、后端说超了”

/** 这些目录整个不显示：依赖、构建产物、缓存 */
const SKIPPED_DIRS = new Set(['node_modules', 'target', 'build', 'dist', 'out', 'bin', 'obj', 'vendor', '__pycache__'])

/** 常见源码，默认勾选 */
const SOURCE_EXTENSIONS = new Set([
  'java', 'kt', 'go', 'py', 'js', 'ts', 'vue', 'jsx', 'tsx', 'cs', 'c', 'cpp', 'h', 'rs', 'php', 'rb', 'scala',
])

/** 配置文件：显示但默认不勾，里面可能有密码和密钥 */
const CONFIG_EXTENSIONS = new Set(['xml', 'yml', 'yaml', 'properties', 'json'])

/** 其他文本：显示但默认不勾 */
const OTHER_TEXT_EXTENSIONS = new Set(['sql', 'md'])

/** 路径里有这些目录时默认不勾：数据类没有深挖价值；#89 实验里去掉 dto、entity、mapper 省约 20%，主题基本不变 */
const DATA_DIRS = new Set(['dto', 'entity', 'mapper', 'model'])

const TEST_DIRS = new Set(['test', 'tests'])

/** 超过这个大小的文件不显示、也不读：多半是生成的文件，单个就可能超过总字数的上限 */
export const MAX_FILE_BYTES = 1024 * 1024

/** 一个可以勾选的文件 */
export interface CodeFileEntry {
  /** 相对项目文件夹的路径，分隔符为 / */
  path: string
  content: string
  /** 统一换行后的字符数 */
  chars: number
  /** 默认是否勾选 */
  defaultChecked: boolean
  /** 默认不勾的原因，默认勾选的为 null */
  reason: string | null
}

/** 浏览器给的路径以选中的文件夹名开头（如 wengu-learn/backend/A.java），去掉这一段 */
export function relativePath(webkitRelativePath: string): string {
  const normalized = webkitRelativePath.replace(/\\/g, '/')
  const slash = normalized.indexOf('/')
  return slash < 0 ? normalized : normalized.slice(slash + 1)
}

/** 选中的文件夹名，用作资料名称的默认值 */
export function folderName(webkitRelativePath: string): string {
  return webkitRelativePath.replace(/\\/g, '/').split('/')[0]
}

/**
 * 按路径决定这个文件显不显示、默认勾不勾（决策 050 第 1 条）。不显示时返回 null，这样的文件也不读内容。
 */
export function classify(path: string): { defaultChecked: boolean; reason: string | null } | null {
  const segments = path.split('/')
  const fileName = segments[segments.length - 1]
  const dirs = segments.slice(0, -1).map((d) => d.toLowerCase())
  if (fileName.startsWith('.') || dirs.some((d) => d.startsWith('.') || SKIPPED_DIRS.has(d))) {
    return null
  }
  const dot = fileName.lastIndexOf('.')
  const ext = dot < 0 ? '' : fileName.slice(dot + 1).toLowerCase()
  if (CONFIG_EXTENSIONS.has(ext)) {
    return { defaultChecked: false, reason: '配置文件可能有密码和密钥' }
  }
  if (OTHER_TEXT_EXTENSIONS.has(ext)) {
    return { defaultChecked: false, reason: '不是程序代码' }
  }
  if (!SOURCE_EXTENSIONS.has(ext)) {
    return null
  }
  if (dirs.some((d) => TEST_DIRS.has(d))) {
    return { defaultChecked: false, reason: '测试代码' }
  }
  if (dirs.some((d) => DATA_DIRS.has(d))) {
    return { defaultChecked: false, reason: '数据类，一般不用学' }
  }
  return { defaultChecked: true, reason: null }
}

/** 字符数：\r\n 统一成 \n 之后再数，和后端一致 */
export function countChars(content: string): number {
  return content.replace(/\r\n/g, '\n').length
}

/** 空文件和二进制文件（含空字符）后端会拒绝，读出来后直接不显示 */
export function isUsableText(content: string): boolean {
  return content.trim() !== '' && !content.includes('\0')
}

/** el-tree 的一个节点。目录的 id 以 / 结尾，和文件区分开 */
export interface CodeTreeNode {
  id: string
  label: string
  /** 文件才有 */
  file?: CodeFileEntry
  children?: CodeTreeNode[]
}

interface DirBuilder {
  dirs: Map<string, DirBuilder>
  files: CodeFileEntry[]
}

/**
 * 按路径建文件树：每层目录在前、文件在后，各自按名字排序。
 * 只有一个子目录、没有文件的目录和下一层合并成一行（如 src/main/java/com/wengu），免得一层层点开。
 */
export function buildTree(files: CodeFileEntry[]): CodeTreeNode[] {
  const root: DirBuilder = { dirs: new Map(), files: [] }
  for (const file of files) {
    const segments = file.path.split('/')
    let dir = root
    for (const segment of segments.slice(0, -1)) {
      let next = dir.dirs.get(segment)
      if (!next) {
        next = { dirs: new Map(), files: [] }
        dir.dirs.set(segment, next)
      }
      dir = next
    }
    dir.files.push(file)
  }
  return toNodes(root, '')
}

function toNodes(dir: DirBuilder, prefix: string): CodeTreeNode[] {
  const dirNodes = [...dir.dirs.entries()]
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([name, child]) => {
      let label = name
      let current = child
      // 合并只有一个子目录的目录
      while (current.files.length === 0 && current.dirs.size === 1) {
        const [nextName, next] = [...current.dirs.entries()][0]
        label = `${label}/${nextName}`
        current = next
      }
      const path = `${prefix}${label}/`
      return { id: path, label, children: toNodes(current, path) }
    })
  const fileNodes = [...dir.files]
    .sort((a, b) => a.path.localeCompare(b.path))
    .map((file) => ({ id: file.path, label: file.path.slice(file.path.lastIndexOf('/') + 1), file }))
  return [...dirNodes, ...fileNodes]
}

/** 字符数写成“1.2 万”或“860”，和“上限 15 万”的说法一致 */
export function formatChars(chars: number): string {
  return chars >= 10000 ? `${(chars / 10000).toFixed(1).replace(/\.0$/, '')} 万` : `${chars}`
}

/** 要点按行拆开：以“-”开头的是得分点（决策 050），确认页把它们缩进显示 */
export function summaryLines(summary: string): { text: string; scorePoint: boolean }[] {
  return summary
    .split('\n')
    .map((line) => line.trim())
    .filter((line) => line !== '')
    .map((line) => (line.startsWith('-') ? { text: line.replace(/^-\s*/, ''), scorePoint: true } : { text: line, scorePoint: false }))
}
