<script setup lang="ts">
// 导入资料 /import（ui.md 5.7）的第 1、2 步：粘贴原文或上传文件 → 识别（或整理）题目 → 勾选题目 → 开始抽取。
// 开始抽取后换成 /drafts/:id，第 3 步的进度在那里显示，刷新、离开后再回来都能接着看（决策 028）。
// 第 1、2 步只在页面内存中：确认题目时刷新，识别结果会丢，要重新识别
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createDraft } from '@/api/drafts'
import { ApiError } from '@/api/http'
import {
  importSource,
  MAX_UPLOAD_BYTES,
  UPLOAD_ACCEPT,
  uploadSource,
  type ImportedSource,
  type SourceType,
} from '@/api/sources'
import PageHeader from '@/components/PageHeader.vue'
import { modKeyLabel, useShortcut } from '@/shortcuts/useShortcut'
import ImageUploader from './ImageUploader.vue'
import ImportSidebar from './ImportSidebar.vue'
import ImportSteps from './ImportSteps.vue'

const router = useRouter()

/** 粘贴文本、上传文件还是截图。截图识别完切回粘贴文本，用户核对后再识别题目（决策 044） */
const mode = ref<'paste' | 'upload' | 'image'>('paste')
const uploader = ref<InstanceType<typeof ImageUploader> | null>(null)
/** 内容框里的文字刚从截图识别出来，提示用户核对 */
const recognizedFromImages = ref(false)
/** 资料类型（#26，决策 037）：题目清单从原文中识别题目，讲解资料根据内容整理题目 */
const type = ref<SourceType>('QUESTION')
const name = ref('')
const content = ref('')
const file = ref<File | null>(null)
const fileInput = ref<HTMLInputElement | null>(null)
/** 识别出的题；为 null 时在第 1 步 */
const source = ref<ImportedSource | null>(null)
const selected = ref<Set<number>>(new Set())
const busy = ref(false)
const error = ref<string | null>(null)

const step = computed(() => (source.value ? 2 : 1))
const isDocument = computed(() => type.value === 'DOCUMENT')
/** 第 1 步主按钮和第 2 步标题里的动作：识别（逐字找出原题）或整理（根据内容出题） */
const verb = computed(() => (isDocument.value ? '整理' : '识别'))

function pickFile(event: Event) {
  const input = event.target as HTMLInputElement
  const picked = input.files?.[0] ?? null
  // 清空 input：移除后再选同一个文件也能触发 change
  input.value = ''
  if (picked && picked.size > MAX_UPLOAD_BYTES) {
    error.value = '文件不能超过 10 MB'
    return
  }
  file.value = picked
  error.value = null
}

function selectMode(value: typeof mode.value) {
  mode.value = value
  error.value = null
  recognizedFromImages.value = false
}

/** 截图识别好了：文字填进内容框（已有内容时追加在后面，可以分几批截图），用户核对后和粘贴的一样继续 */
function onImagesRecognized(text: string) {
  content.value = content.value.trim() === '' ? text : `${content.value.trimEnd()}\n\n${text}`
  if (name.value.trim() === '') {
    name.value = '截图导入'
  }
  selectMode('paste')
  recognizedFromImages.value = true
}

function formatSize(bytes: number): string {
  return bytes < 1024 * 1024 ? `${Math.max(1, Math.round(bytes / 1024))} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

/** 第 1 步缺什么时的提示；齐了返回 null */
function missing(): string | null {
  if (mode.value === 'upload') {
    return file.value ? null : '请选择要上传的文件'
  }
  if (name.value.trim() === '') {
    return '请填写资料名称'
  }
  return content.value.trim() === '' ? (isDocument.value ? '请粘贴资料内容' : '请粘贴面试题') : null
}

async function recognize() {
  if (busy.value) {
    return
  }
  error.value = missing()
  if (error.value) {
    return
  }
  busy.value = true
  try {
    const imported =
      mode.value === 'upload' && file.value
        ? await uploadSource(file.value, name.value, type.value)
        : await importSource(name.value.trim(), content.value, type.value)
    source.value = imported
    selected.value = new Set(imported.questions.map((q) => q.id))
  } catch (e) {
    // 识别失败、原文过长、文件读不出文字等，后端的提示已经写清楚了怎么办
    error.value = e instanceof ApiError ? e.message : `题目${verb.value}失败，可以重试一次`
  } finally {
    busy.value = false
  }
}

function toggle(id: number, checked: boolean) {
  const next = new Set(selected.value)
  if (checked) {
    next.add(id)
  } else {
    next.delete(id)
  }
  selected.value = next
}

/** 回到第 1 步：原文和选好的文件都还在，改完再识别一次 */
function backToPaste() {
  source.value = null
  error.value = null
}

async function startExtraction() {
  if (!source.value || busy.value || selected.value.size === 0) {
    return
  }
  busy.value = true
  error.value = null
  try {
    // 按原文顺序传题号
    const ids = source.value.questions.map((q) => q.id).filter((id) => selected.value.has(id))
    const draft = await createDraft(source.value.id, ids)
    await router.replace(`/drafts/${draft.id}`)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '没有开始抽取，请重试'
    busy.value = false
  }
}

// 第 1 步 Ctrl Enter 识别（焦点在输入框中也生效）；第 2 步 Enter 开始抽取
useShortcut('Mod+Enter', () => {
  if (step.value === 1) {
    void (mode.value === 'image' ? uploader.value?.submit() : recognize())
  }
})
useShortcut('Enter', () => {
  if (step.value === 2) {
    void startExtraction()
  }
})
</script>

<template>
  <PageHeader title="导入资料">
    <ImportSteps :current="step" />
  </PageHeader>
  <div class="wg-page-body layout">
    <main class="main">
      <!-- 第 1 步：粘贴或上传 -->
      <section v-if="!source" class="panel">
        <div class="segmented" role="tablist" aria-label="导入方式">
          <button
            v-for="m in [{ value: 'paste', label: '粘贴文本' }, { value: 'upload', label: '上传文件' }, { value: 'image', label: '截图' }] as const"
            :key="m.value"
            type="button"
            role="tab"
            class="segment"
            :class="{ active: mode === m.value }"
            :aria-selected="mode === m.value"
            @click="selectMode(m.value)"
          >
            {{ m.label }}
          </button>
        </div>
        <div class="field">
          <span id="source-type-label" class="label">资料类型</span>
          <div class="segmented" role="radiogroup" aria-labelledby="source-type-label">
            <button
              v-for="t in [{ value: 'QUESTION', label: '题目清单' }, { value: 'DOCUMENT', label: '讲解资料（笔记、文章）' }] as const"
              :key="t.value"
              type="button"
              role="radio"
              class="segment"
              :class="{ active: type === t.value }"
              :aria-checked="type === t.value"
              @click="type = t.value"
            >
              {{ t.label }}
            </button>
          </div>
          <p class="hint">
            {{
              isDocument
                ? '没有现成题目的笔记、技术文章、书的章节：会根据内容整理出面试题，每道题都能用资料回答，下一步可以挑选。'
                : '一行一题、带答案、表格、从网页复制的都可以，会自动识别出题目。答案会保存下来，但抽取时只看题目。'
            }}
          </p>
        </div>
        <div class="field">
          <label for="source-name">资料名称</label>
          <el-input
            id="source-name"
            v-model="name"
            :placeholder="mode === 'upload' ? '不填时用文件名' : mode === 'image' ? '不填时写“截图导入”' : isDocument ? '如：线程池学习笔记' : '如：Java 后端面试题（第一批）'"
            maxlength="255"
          />
        </div>
        <div v-if="mode === 'paste'" class="field">
          <label for="source-content">内容</label>
          <p v-if="recognizedFromImages" class="recognized">识别好了，核对一下，错的地方可以直接改</p>
          <el-input
            id="source-content"
            v-model="content"
            type="textarea"
            :rows="14"
            resize="vertical"
            :placeholder="isDocument ? '把笔记或文章粘贴到这里' : '把面试题粘贴到这里'"
          />
        </div>
        <ImageUploader v-else-if="mode === 'image'" ref="uploader" @recognized="onImagesRecognized" />
        <div v-else class="field">
          <span class="label">文件</span>
          <input ref="fileInput" type="file" class="file-input" :accept="UPLOAD_ACCEPT" @change="pickFile" />
          <div v-if="file" class="file">
            <span class="file-name">{{ file.name }}</span>
            <span class="hint">{{ formatSize(file.size) }}</span>
            <button type="button" class="remove" @click="file = null">移除</button>
          </div>
          <el-button v-else class="pick" @click="fileInput?.click()">选择文件</el-button>
          <p class="hint">支持 PDF、Word（.docx）、Markdown 和 txt，最大 10 MB。扫描件（图片）的 PDF 暂不支持。</p>
        </div>
        <!-- 截图有自己的主按钮：先识别文字，核对后才识别题目 -->
        <template v-if="mode !== 'image'">
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <div class="row">
            <el-button type="primary" class="primary" :loading="busy" @click="recognize">
              {{ verb }}题目<kbd>{{ modKeyLabel }} Enter</kbd>
            </el-button>
            <span v-if="busy" class="hint">
              {{ isDocument ? '正在根据内容整理题目，资料长时要一两分钟' : '正在识别题目，题目多时要一两分钟' }}
            </span>
          </div>
        </template>
      </section>

      <!-- 第 2 步：确认题目 -->
      <template v-else>
        <section class="panel">
          <div class="questions-head">
            <h2>{{ source.type === 'DOCUMENT' ? '整理' : '识别' }}出 {{ source.questions.length }} 道题</h2>
            <span class="hint">已选 {{ selected.size }} 道</span>
          </div>
          <p class="hint">
            {{
              source.type === 'DOCUMENT'
                ? '这些题是根据资料内容整理的，不想学的去掉勾选就行。不满意可以回上一步再整理一次。'
                : '不是题目的去掉勾选就行。漏了题或者识别错了，可以回去改原文再识别一次。'
            }}
          </p>
          <ul class="questions">
            <li v-for="q in source.questions" :key="q.id" class="question">
              <el-checkbox
                :model-value="selected.has(q.id)"
                :aria-label="`第 ${q.id} 题`"
                @change="(v: string | number | boolean) => toggle(q.id, v === true)"
              />
              <div class="question-text" :class="{ off: !selected.has(q.id) }">
                <span class="no">{{ q.id }}</span>
                <div>
                  <p>{{ q.text }}</p>
                  <p v-for="f in q.followUps" :key="f" class="follow-up">追问：{{ f }}</p>
                </div>
              </div>
            </li>
          </ul>
        </section>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div class="row between">
          <el-button class="back" @click="backToPaste">{{ mode === 'paste' ? '← 回去改原文' : '← 回上一步' }}</el-button>
          <el-button
            type="primary"
            class="primary"
            :loading="busy"
            :disabled="selected.size === 0"
            @click="startExtraction"
          >
            开始抽取知识点（{{ selected.size }} 道）<kbd>Enter</kbd>
          </el-button>
        </div>
      </template>
    </main>
    <ImportSidebar />
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 28px;
  padding-top: 32px;
}

.main {
  flex: 999 1 620px;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.panel {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 28px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

/* 分段控件（ui.md 第 3 节）：底色 tag-bg，选中项白底加粗 */
.segmented {
  align-self: flex-start;
  display: flex;
  gap: 4px;
  padding: 4px;
  border-radius: var(--wg-radius-control);
  background: var(--wg-tag-bg);
}

.segment {
  min-height: 40px;
  padding: 0 16px;
  border: none;
  border-radius: var(--wg-radius-segment);
  background: none;
  color: var(--wg-text-muted);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.segment.active {
  background: var(--wg-bg);
  color: var(--wg-text);
  font-weight: 700;
}

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

.field .segmented {
  align-self: flex-start;
}

/* 原生的文件选择框不好看，藏起来，用“选择文件”按钮触发 */
.file-input {
  display: none;
}

.pick {
  align-self: flex-start;
  height: 44px;
}

.file {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px 14px;
  min-height: 44px;
  padding: 8px 14px;
  border: 1px solid var(--wg-border-strong);
  border-radius: var(--wg-radius-control);
}

.file-name {
  min-width: 0;
  overflow-wrap: anywhere;
  font-size: 15px;
}

.remove {
  margin-left: auto;
  padding: 4px 8px;
  border: none;
  border-radius: var(--wg-radius-control);
  background: none;
  color: var(--wg-warn);
  font: inherit;
  font-size: 14px;
  cursor: pointer;
}

.remove:hover {
  background: var(--wg-warn-soft);
}

.hint {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.error {
  color: var(--wg-warn);
  font-size: 14px;
}

.recognized {
  color: var(--wg-success);
  font-size: 14px;
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

.questions-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
}

h2 {
  font-size: 20px;
}

/* 题多时在自己的容器内滚动，按钮一直在视野里 */
.questions {
  max-height: 560px;
  margin: 0;
  padding: 0;
  overflow-y: auto;
  list-style: none;
}

.question {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid var(--wg-border);
}

.question-text {
  display: flex;
  gap: 10px;
  font-size: 15px;
  line-height: 1.7;
}

.question-text.off {
  opacity: 0.5;
}

.no {
  min-width: 20px;
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.8;
}

.follow-up {
  color: var(--wg-text-muted);
  font-size: 14px;
}

@media (max-width: 600px) {
  .layout {
    padding-top: 20px;
  }

  .panel {
    padding: 20px;
  }
}
</style>
