<script setup lang="ts">
// 源码导入（ui.md 5.7，#90，决策 050）的第 1、2 步：选择项目文件夹 → 勾选文件 → 开始抽取。
// 文件内容在浏览器里读出，只发勾选的文字；开始抽取后跳到 /drafts/:id，第 3 步和题目抽取相同。
// 和题目导入一样，第 1、2 步只在页面内存中，刷新要重新选文件夹
import { computed, nextTick, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { ElTree } from 'element-plus'
import { createDraft } from '@/api/drafts'
import { ApiError } from '@/api/http'
import { importCode, MAX_CODE_CHARS, MAX_CODE_FILES, MAX_CODE_PATH_LENGTH } from '@/api/sources'
import { modKeyLabel } from '@/shortcuts/useShortcut'
import {
  buildTree,
  classify,
  countChars,
  folderName,
  formatChars,
  isUsableText,
  MAX_FILE_BYTES,
  relativePath,
  type CodeFileEntry,
  type CodeTreeNode,
} from './codeFiles'

const emit = defineEmits<{ step: [step: 1 | 2] }>()
const router = useRouter()

const folderInput = ref<HTMLInputElement | null>(null)
const tree = ref<InstanceType<typeof ElTree> | null>(null)
/** 选好了文件夹，在第 2 步；为 null 时在第 1 步 */
const entries = ref<CodeFileEntry[] | null>(null)
const checked = ref<Set<string>>(new Set())
const name = ref('')
const reading = ref(false)
const busy = ref(false)
const error = ref<string | null>(null)

const nodes = computed<CodeTreeNode[]>(() => (entries.value ? buildTree(entries.value) : []))
/** 一开始只展开第一层，勾选状态在折叠的目录上也看得出（半选） */
const expandedKeys = computed(() => nodes.value.filter((n) => n.children).map((n) => n.id))
const selectedFiles = computed(() => (entries.value ?? []).filter((f) => checked.value.has(f.path)))
const totalChars = computed(() => selectedFiles.value.reduce((n, f) => n + f.chars, 0))
const overBy = computed(() => totalChars.value - MAX_CODE_CHARS)

/** 不能开始抽取的原因；能开始时为 null */
const blocker = computed(() => {
  if (selectedFiles.value.length === 0) {
    return '请至少勾选一个文件'
  }
  if (overBy.value > 0) {
    return `超出 ${overBy.value} 字符。取消勾选一些文件，或者只选一条业务链路`
  }
  if (selectedFiles.value.length > MAX_CODE_FILES) {
    return `一次最多 ${MAX_CODE_FILES} 个文件，现在勾选了 ${selectedFiles.value.length} 个`
  }
  return null
})

function pickFolder() {
  error.value = null
  folderInput.value?.click()
}

/** 只读认得的文件：依赖、构建产物、不认识的扩展名、太大的文件连内容都不读；空文件和二进制文件读完再去掉 */
async function onFolderPicked(event: Event) {
  const input = event.target as HTMLInputElement
  const picked = Array.from(input.files ?? [])
  // 清空 input：重新选同一个文件夹也能触发 change
  input.value = ''
  if (picked.length === 0) {
    return
  }
  reading.value = true
  error.value = null
  try {
    const candidates = picked
      .map((file) => ({ file, path: relativePath(file.webkitRelativePath), rule: null as ReturnType<typeof classify> }))
      .map((c) => ({ ...c, rule: classify(c.path) }))
      .filter((c) => c.rule !== null && c.file.size <= MAX_FILE_BYTES && c.path.length <= MAX_CODE_PATH_LENGTH)
    const read = await Promise.all(
      candidates.map(async ({ file, path, rule }) => {
        const content = await file.text()
        return isUsableText(content)
          ? { path, content, chars: countChars(content), defaultChecked: rule!.defaultChecked, reason: rule!.reason }
          : null
      }),
    )
    const usable = read.filter((f): f is CodeFileEntry => f !== null)
    name.value = folderName(picked[0].webkitRelativePath)
    entries.value = usable
    checked.value = new Set(usable.filter((f) => f.defaultChecked).map((f) => f.path))
    emit('step', 2)
    await nextTick()
    tree.value?.setCheckedKeys([...checked.value], true)
  } catch {
    error.value = '读取文件夹失败，请重新选择'
  } finally {
    reading.value = false
  }
}

/** el-tree 勾选变化：只记文件，目录的勾选由它们下面的文件推出来 */
function onCheck() {
  const keys = (tree.value?.getCheckedKeys(true) ?? []) as string[]
  checked.value = new Set(keys.filter((k) => !k.endsWith('/')))
}

function reselect() {
  entries.value = null
  checked.value = new Set()
  error.value = null
  emit('step', 1)
}

/** 第 1 步打开文件夹选择框，第 2 步开始抽取；Ctrl Enter 由 ImportPage 转过来 */
async function submit() {
  if (!entries.value) {
    pickFolder()
    return
  }
  if (busy.value) {
    return
  }
  if (name.value.trim() === '') {
    error.value = '请填写资料名称'
    return
  }
  error.value = blocker.value
  if (error.value) {
    return
  }
  busy.value = true
  try {
    const source = await importCode(
      name.value.trim(),
      selectedFiles.value.map((f) => ({ path: f.path, content: f.content })),
    )
    const draft = await createDraft(source.id)
    await router.replace(`/drafts/${draft.id}`)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '没有开始抽取，请重试'
    busy.value = false
  }
}

defineExpose({ submit })
</script>

<template>
  <!-- 第 1 步：选择文件夹 -->
  <div v-if="!entries" class="field">
    <span class="label">项目文件夹</span>
    <input ref="folderInput" type="file" class="file-input" webkitdirectory multiple @change="onFolderPicked" />
    <el-button class="pick" :loading="reading" @click="pickFolder">选择项目文件夹</el-button>
    <p class="hint">选一条完整的业务链路效果最好，比如从接口到入库的那几个文件，不必整个项目。注释越清楚，抽出的知识点越准。</p>
    <p class="hint">勾选的文件会发送给你设置的 AI 模型。</p>
    <p v-if="reading" class="hint">正在读取文件……</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
  </div>

  <!-- 第 2 步：勾选文件 -->
  <template v-else>
    <div class="field">
      <label for="code-name">资料名称</label>
      <el-input id="code-name" v-model="name" maxlength="255" />
    </div>
    <div v-if="entries.length === 0" class="field">
      <p>这个文件夹里没有找到源码文件</p>
      <el-button class="pick" @click="reselect">重新选择</el-button>
    </div>
    <div v-else class="field">
      <p class="count" :class="{ over: overBy > 0 }" aria-live="polite">
        已选 {{ selectedFiles.length }} 个文件，共 {{ formatChars(totalChars) }} 字符（上限 {{ formatChars(MAX_CODE_CHARS) }}）
      </p>
      <div class="tree-box">
        <el-tree
          ref="tree"
          :data="nodes"
          node-key="id"
          show-checkbox
          :default-expanded-keys="expandedKeys"
          :expand-on-click-node="false"
          @check="onCheck"
        >
          <template #default="{ data }">
            <span class="node">
              <span class="node-label">{{ data.label }}</span>
              <template v-if="data.file">
                <span class="node-meta">{{ formatChars(data.file.chars) }}</span>
                <span v-if="data.file.reason" class="node-meta">{{ data.file.reason }}</span>
              </template>
            </span>
          </template>
        </el-tree>
      </div>
    </div>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <!-- 超出上限、文件太多时说明原因；一个都没勾时按钮不可用已经够清楚 -->
    <p v-else-if="blocker && selectedFiles.length > 0" class="error" role="alert">{{ blocker }}</p>
    <div class="row between">
      <el-button class="back" @click="reselect">← 重新选择文件夹</el-button>
      <el-button
        type="primary"
        class="primary"
        :loading="busy"
        :disabled="entries.length === 0 || blocker !== null"
        @click="submit"
      >
        开始抽取知识点（{{ selectedFiles.length }} 个文件）<kbd>{{ modKeyLabel }} Enter</kbd>
      </el-button>
    </div>
  </template>
</template>

<style scoped>
.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

label,
.label {
  font-size: 14px;
  font-weight: 700;
}

/* 原生的选择框不好看，藏起来，用按钮触发 */
.file-input {
  display: none;
}

.pick {
  align-self: flex-start;
  height: 44px;
}

.hint {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.error {
  color: var(--wg-warn);
  font-size: 14px;
}

.count {
  font-size: 14px;
}

.count.over {
  color: var(--wg-danger);
  font-weight: 700;
}

/* 文件多时在自己的容器内滚动，按钮一直在视野里 */
.tree-box {
  max-height: 560px;
  padding: 8px 0;
  overflow: auto;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-control);
}

.node {
  display: inline-flex;
  align-items: baseline;
  gap: 10px;
  min-width: 0;
  font-size: 14px;
}

.node-label {
  overflow-wrap: anywhere;
}

.node-meta {
  color: var(--wg-text-muted);
  font-size: 12px;
  white-space: nowrap;
}

.row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px 16px;
}

.row.between {
  justify-content: space-between;
}

.primary {
  height: 48px;
  padding: 0 22px;
  font-size: 16px;
}

.primary kbd {
  margin-left: 12px;
}

.back {
  height: 48px;
}
</style>
