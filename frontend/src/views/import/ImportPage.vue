<script setup lang="ts">
// 导入资料 /import（ui.md 5.7）的第 1、2 步：粘贴原文 → 识别题目 → 勾选题目 → 开始抽取。
// 开始抽取后换成 /drafts/:id，第 3 步的进度在那里显示，刷新、离开后再回来都能接着看（决策 028）。
// 第 1、2 步只在页面内存中：确认题目时刷新，识别结果会丢，要重新识别
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createDraft } from '@/api/drafts'
import { ApiError } from '@/api/http'
import { importQuestions, type ImportedSource } from '@/api/sources'
import PageHeader from '@/components/PageHeader.vue'
import { modKeyLabel, useShortcut } from '@/shortcuts/useShortcut'
import ImportSidebar from './ImportSidebar.vue'
import ImportSteps from './ImportSteps.vue'

const router = useRouter()

const name = ref('')
const content = ref('')
/** 识别出的题；为 null 时在第 1 步 */
const source = ref<ImportedSource | null>(null)
const selected = ref<Set<number>>(new Set())
const busy = ref(false)
const error = ref<string | null>(null)

const step = computed(() => (source.value ? 2 : 1))

async function recognize() {
  if (busy.value) {
    return
  }
  if (name.value.trim() === '' || content.value.trim() === '') {
    error.value = name.value.trim() === '' ? '请填写资料名称' : '请粘贴面试题'
    return
  }
  busy.value = true
  error.value = null
  try {
    const imported = await importQuestions(name.value.trim(), content.value)
    source.value = imported
    selected.value = new Set(imported.questions.map((q) => q.id))
  } catch (e) {
    // 识别失败、原文过长等，后端的提示已经写清楚了怎么办
    error.value = e instanceof ApiError ? e.message : '题目识别失败，可以重试一次，或检查原文格式'
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

/** 回去改原文：原文还在，改完再识别一次 */
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
    void recognize()
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
          <button type="button" role="tab" class="segment active" aria-selected="true">粘贴文本</button>
          <el-tooltip content="即将开放" placement="top">
            <!-- 上传文件由 #26 实现；禁用的按钮不触发鼠标事件，提示挂在外面一层 -->
            <span tabindex="0">
              <button type="button" role="tab" class="segment" aria-selected="false" disabled>上传文件</button>
            </span>
          </el-tooltip>
        </div>
        <div class="field">
          <label for="source-name">资料名称</label>
          <el-input id="source-name" v-model="name" placeholder="如：Java 后端面试题（第一批）" maxlength="255" />
        </div>
        <div class="field">
          <label for="source-content">内容</label>
          <el-input
            id="source-content"
            v-model="content"
            type="textarea"
            :rows="14"
            resize="vertical"
            placeholder="把面试题粘贴到这里"
          />
          <p class="hint">一行一题、带答案、表格、从网页复制的都可以，会自动识别出题目。答案会保存下来，但抽取时只看题目。</p>
        </div>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div class="row">
          <el-button type="primary" class="primary" :loading="busy" @click="recognize">
            识别题目<kbd>{{ modKeyLabel }} Enter</kbd>
          </el-button>
          <span v-if="busy" class="hint">正在识别题目，题目多时要一两分钟</span>
        </div>
      </section>

      <!-- 第 2 步：确认题目 -->
      <template v-else>
        <section class="panel">
          <div class="questions-head">
            <h2>识别出 {{ source.questions.length }} 道题</h2>
            <span class="hint">已选 {{ selected.size }} 道</span>
          </div>
          <p class="hint">不是题目的去掉勾选就行。漏了题或者识别错了，可以回去改原文再识别一次。</p>
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
          <el-button class="back" @click="backToPaste">← 回去改原文</el-button>
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

.segment:disabled {
  cursor: not-allowed;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

label {
  font-size: 14px;
  font-weight: 700;
}

.hint {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.error {
  color: var(--wg-warn);
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
