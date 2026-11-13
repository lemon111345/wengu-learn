<script setup lang="ts">
// 右侧详情（ui.md 5.9）：掌握程度和下次复习；标题、类型、要点可编辑；学习记录、来源；
// 保存修改、暂停或恢复复习、删除。
// 编辑先改在本地副本上，“保存修改”时一起提交。“恢复 AI 版本”只把原文填回编辑框，保存后才生效（决策 029）。
// 删除由页面处理：批量删除也要撤销，两处共用一个撤销提示
import { computed, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import {
  fetchKnowledgePoint,
  resumeKnowledgePoints,
  suspendKnowledgePoints,
  updateKnowledgePoint,
  type KnowledgePointDetail,
} from '@/api/knowledgePoints'
import type { KnowledgePointType } from '@/api/types'
import { formatMonthDay } from '@/utils/date'
import { masteryLabels, typeLabels } from '@/utils/labels'
import MasteryDot from './MasteryDot.vue'
import ReferenceAnswer from '@/views/history/ReferenceAnswer.vue'
import { nextReviewText, sourceText, studyRecordText } from './format'

const props = defineProps<{ id: number }>()
const emit = defineEmits<{
  /** 保存、暂停、恢复之后：列表中的标题、类型、掌握程度可能变了 */
  changed: []
  delete: [id: number]
}>()
const router = useRouter()

const detail = ref<KnowledgePointDetail | null>(null)
const loadError = ref<string | null>(null)
const busy = ref(false)
const form = reactive<{ title: string; type: KnowledgePointType; summary: string }>({
  title: '',
  type: 'CONCEPT',
  summary: '',
})

const typeOptions = Object.entries(typeLabels) as [KnowledgePointType, string][]

// 快速切换知识点时，只用最后一次请求的结果
let latestRequest = 0

async function load() {
  const request = ++latestRequest
  try {
    const result = await fetchKnowledgePoint(props.id)
    if (request !== latestRequest) {
      return
    }
    detail.value = result
    loadError.value = null
    form.title = result.title
    form.type = result.type
    form.summary = result.summary
  } catch (e) {
    if (request === latestRequest) {
      detail.value = null
      loadError.value = e instanceof ApiError ? e.message : '详情没有加载出来，请重试'
    }
  }
}

/** 参考答法用最近一次有会话的学习记录（记录最近的在前）；合并进来的记录也算 */
const latestSessionId = computed(() => detail.value?.reviews.find((r) => r.sessionId !== null)?.sessionId ?? null)
const answerOpen = ref(false)

watch(
  () => props.id,
  () => {
    detail.value = null
    answerOpen.value = false
    void load()
  },
  { immediate: true },
)

/** 和后端的内容比，首尾空白不算：后端保存时会去掉 */
const dirty = computed(() => {
  const d = detail.value
  return (
    d !== null &&
    (form.title.trim() !== d.title || form.type !== d.type || form.summary.trim() !== d.summary.trim())
  )
})

/** 要点是否和 AI 原文不同，按编辑框当前的内容实时判断；没有 AI 原文时不显示胶囊 */
const summaryEdited = computed(() => {
  const ai = detail.value?.aiSummary
  return ai != null && form.summary.trim() !== ai.trim()
})

const nextLabel = computed(() => {
  const d = detail.value
  return d?.nextReview ? `下次复习：${nextReviewText(d).text}` : ''
})

function restoreAiSummary() {
  if (detail.value?.aiSummary != null) {
    form.summary = detail.value.aiSummary.trim()
  }
}

/**
 * dirty：页面切换知识点、离开页面前问一下有没有没保存的修改；
 * reload：批量操作改到了正在看的知识点时，页面让详情重新取一次
 */
defineExpose({ dirty, reload: load })

async function run(action: () => Promise<void>, done: string, failed: string) {
  if (busy.value) {
    return
  }
  busy.value = true
  try {
    await action()
    ElMessage.success(done)
    await load()
    emit('changed')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : failed)
  } finally {
    busy.value = false
  }
}

function save() {
  const d = detail.value
  if (!d || !dirty.value) {
    return
  }
  // 只传改了的字段（PATCH 语义），后端只在确实有改动时留快照
  const changes: { title?: string; type?: KnowledgePointType; summary?: string } = {}
  if (form.title.trim() !== d.title) changes.title = form.title
  if (form.type !== d.type) changes.type = form.type
  if (form.summary.trim() !== d.summary.trim()) changes.summary = form.summary
  void run(() => updateKnowledgePoint(d.id, changes), '已保存', '没有保存成功，请重试')
}

function toggleSuspend() {
  const d = detail.value
  if (!d) {
    return
  }
  if (d.status === 'SUSPENDED') {
    void run(() => resumeKnowledgePoints([d.id]), '已恢复复习', '没有恢复成功，请重试')
  } else {
    void run(() => suspendKnowledgePoints([d.id]), '已暂停复习', '没有暂停成功，请重试')
  }
}
</script>

<template>
  <aside class="detail" aria-label="知识点详情">
    <div v-if="loadError" class="load-error" role="alert">
      <p>{{ loadError }}</p>
      <el-button @click="load">重试</el-button>
    </div>
    <el-skeleton v-else-if="!detail" :rows="8" animated aria-busy="true" />
    <template v-else>
      <div class="state">
        <MasteryDot :mastery="detail.mastery" />{{ masteryLabels[detail.mastery] }}
        <span class="next">{{ nextLabel }}</span>
        <!-- 只有待学的能开始学（#81）。有没保存的修改时，页面离开前的确认弹窗同样会问 -->
        <el-button v-if="detail.mastery === 'NEW'" @click="router.push(`/study/${detail!.id}`)">开始学</el-button>
      </div>

      <div class="field">
        <label for="detail-title">标题</label>
        <el-input
          id="detail-title"
          v-model="form.title"
          class="title-input"
          type="textarea"
          :autosize="{ minRows: 2, maxRows: 5 }"
          maxlength="500"
        />
      </div>

      <div class="field">
        <label for="detail-type">类型</label>
        <el-select id="detail-type" v-model="form.type">
          <el-option v-for="[value, label] in typeOptions" :key="value" :value="value" :label="label" />
        </el-select>
      </div>

      <div class="field">
        <div class="summary-head">
          <label for="detail-summary" class="summary-label">
            要点
            <span v-if="detail.aiSummary != null" class="badge" :class="{ edited: summaryEdited }">
              {{ summaryEdited ? '你改过' : 'AI 生成' }}
            </span>
          </label>
          <button v-if="summaryEdited" type="button" class="link" @click="restoreAiSummary">恢复 AI 版本</button>
        </div>
        <el-input
          id="detail-summary"
          v-model="form.summary"
          type="textarea"
          :autosize="{ minRows: 5, maxRows: 14 }"
        />
        <p class="hint">
          要点是评分的标准，由 AI 根据原题写成，可能有遗漏或错误。手上有参考答案的话，可以照着改；不要为了好过而删掉难的部分。
        </p>
      </div>

      <!-- 默认折叠：知识库里也能看到还没到期的，直接显示等于复习前先看了答案（ui.md 5.9，#111） -->
      <section v-if="latestSessionId !== null" class="block divided">
        <div class="answer-head">
          <h2>面试参考答法</h2>
          <span class="muted">最近一次学习</span>
          <button type="button" class="link toggle" :aria-expanded="answerOpen" @click="answerOpen = !answerOpen">
            {{ answerOpen ? '收起' : '展开' }}
          </button>
        </div>
        <ReferenceAnswer v-if="answerOpen" :key="latestSessionId" :session-id="latestSessionId" />
      </section>

      <section class="block divided">
        <h2>学习记录</h2>
        <p v-if="detail.reviews.length === 0" class="muted">还没有学过</p>
        <ul v-else class="records">
          <li v-for="(record, i) in detail.reviews" :key="i">
            <span class="muted">{{ formatMonthDay(record.date) }}</span>
            <span class="record-text">
              <RouterLink v-if="record.sessionId !== null" :to="`/history/${record.sessionId}`">
                {{ studyRecordText(record) }}
              </RouterLink>
              <template v-else>{{ studyRecordText(record) }}</template>
              <span v-if="record.mergedFrom" class="merged">合并自：{{ record.mergedFrom }}</span>
            </span>
          </li>
        </ul>
      </section>

      <section class="block">
        <h2>来源</h2>
        <p v-if="detail.sources.length === 0" class="muted">没有记录来源</p>
        <p v-for="source in detail.sources" :key="source.sourceId" class="muted">{{ sourceText(source) }}</p>
      </section>

      <div class="actions divided">
        <el-button type="primary" :disabled="!dirty" :loading="busy && dirty" @click="save">保存修改</el-button>
        <el-button :disabled="busy" @click="toggleSuspend">
          {{ detail.status === 'SUSPENDED' ? '恢复复习' : '暂停复习' }}
        </el-button>
        <button type="button" class="danger" :disabled="busy" @click="emit('delete', detail.id)">删除</button>
      </div>
    </template>
  </aside>
</template>

<style scoped>
/* 面板：白底、1px 描边、圆角 14，不加阴影（ui.md 2.3） */
.detail {
  display: flex;
  flex-direction: column;
  gap: 18px;
  padding: 24px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.state {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--wg-text-muted);
  font-size: 14px;
}

.next {
  margin-left: auto;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

label,
h2 {
  font-size: 14px;
  font-weight: 700;
}

/* 知识点标题用宋体（ui.md 2.2） */
.title-input :deep(textarea) {
  font-family: var(--wg-font-serif);
  font-size: 17px;
  font-weight: 700;
  line-height: 1.5;
}

/* 下拉框和输入框一样高 44（ui.md 3） */
.field :deep(.el-select__wrapper) {
  min-height: 44px;
}

.field :deep(textarea) {
  padding: 10px 12px;
  line-height: 1.75;
}

.summary-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
}

.summary-label {
  display: flex;
  align-items: center;
  gap: 8px;
}

.badge {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--wg-tag-bg);
  color: var(--wg-text-muted);
  font-size: 12px;
  font-weight: 400;
}

.badge.edited {
  background: var(--wg-primary-soft);
  color: var(--wg-primary);
}

.link {
  min-height: 32px;
  padding: 0 4px;
  border: none;
  background: transparent;
  color: var(--wg-primary);
  font: inherit;
  font-size: 13px;
  cursor: pointer;
}

.link:hover {
  color: var(--wg-primary-hover);
}

.hint {
  color: var(--wg-text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.block {
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: 14px;
}

.answer-head {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.answer-head .muted {
  font-size: 13px;
}

.toggle {
  margin-left: auto;
}

.divided {
  padding-top: 16px;
  border-top: 1px solid var(--wg-border);
}

.records {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.records li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.record-text {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  text-align: right;
}

.merged {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.muted {
  color: var(--wg-text-muted);
  line-height: 1.6;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
}

.actions .el-button + .el-button {
  margin-left: 0;
}

/* 危险操作只用文字按钮，放在操作区最右侧（ui.md 3） */
.danger {
  min-height: 44px;
  margin-left: auto;
  padding: 0 10px;
  border: none;
  background: transparent;
  color: var(--wg-warn);
  font: inherit;
  font-size: 15px;
  cursor: pointer;
}

.danger:disabled {
  opacity: 0.5;
  cursor: default;
}

.load-error {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}
</style>
