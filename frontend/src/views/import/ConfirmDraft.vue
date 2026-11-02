<script setup lang="ts">
// 确认知识点（ui.md 5.8）：按学习顺序列出，只能取消勾选、不能修改（草稿保持模型的原始输出）；
// 右侧栏是将加入的数量、按类型的数量、大约几天学完、确认加入；有题没拆出知识点时可以单独再抽。
// 草稿已经确认过时，显示加入了哪些
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmDraft, createDraft, type DraftItemView, type DraftView } from '@/api/drafts'
import { ApiError } from '@/api/http'
import { fetchSettings } from '@/api/settings'
import type { KnowledgePointType } from '@/api/types'
import PageHeader from '@/components/PageHeader.vue'
import { modKeyLabel, useShortcut } from '@/shortcuts/useShortcut'
import { typeLabels } from '@/utils/labels'

const props = defineProps<{ draft: DraftView }>()
const router = useRouter()

/** 只收录、暂时不能学的类型（决策 007），不算进“几天学完” */
const UNSCHEDULED: KnowledgePointType[] = ['SYSTEM_DESIGN', 'EXPERIENCE']

const confirmed = computed(() => props.draft.status === 'CONFIRMED')
const rejected = ref<Set<number>>(new Set())
const busy = ref(false)
const dailyNewLimit = ref<number | null>(null)

onMounted(async () => {
  try {
    dailyNewLimit.value = (await fetchSettings()).dailyNewLimit
  } catch {
    // 取不到时不显示“几天学完”
  }
})

const questionCount = computed(() => props.draft.batches.reduce((n, b) => n + b.questionIds.length, 0))
const accepted = computed(() =>
  props.draft.items.filter((i) => (confirmed.value ? i.decision === 'ACCEPTED' : !rejected.value.has(i.id))),
)
const scheduledCount = computed(() => accepted.value.filter((i) => !UNSCHEDULED.includes(i.type)).length)
const typeCounts = computed(() => {
  const counts = new Map<KnowledgePointType, number>()
  accepted.value.forEach((i) => counts.set(i.type, (counts.get(i.type) ?? 0) + 1))
  return [...counts].sort((a, b) => b[1] - a[1])
})
const daysText = computed(() => {
  const limit = dailyNewLimit.value
  if (limit === null || scheduledCount.value === 0) {
    return ''
  }
  if (limit === 0) {
    return '每天新学设为 0，这一批暂时不会安排'
  }
  return `按每天新学 ${limit} 个，大约 ${Math.ceil(scheduledCount.value / limit)} 天学完这一批`
})

function isOn(item: DraftItemView) {
  return confirmed.value ? item.decision === 'ACCEPTED' : !rejected.value.has(item.id)
}

function toggle(item: DraftItemView, checked: boolean) {
  const next = new Set(rejected.value)
  if (checked) {
    next.delete(item.id)
  } else {
    next.add(item.id)
  }
  rejected.value = next
}

function sources(item: DraftItemView) {
  return `来自第 ${item.sourceQuestionIds.join('、')} 题`
}

async function confirm() {
  if (confirmed.value || busy.value) {
    return
  }
  busy.value = true
  try {
    await confirmDraft(props.draft.id, [...rejected.value])
    const n = accepted.value.length
    ElMessage.success(n > 0 ? `已加入 ${n} 个知识点` : '这一批都没有加入')
    await router.push('/')
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '没有确认成功，请重试')
    busy.value = false
  }
}

/** 单独再抽没拆出知识点的题：另起一份草稿，这一份之后照样确认（决策 028） */
async function reExtract() {
  if (props.draft.sourceId === null || busy.value) {
    return
  }
  busy.value = true
  try {
    const next = await createDraft(
      props.draft.sourceId,
      props.draft.uncovered.map((q) => q.questionId),
    )
    await router.push(`/drafts/${next.id}`)
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '没有开始抽取，请重试')
  } finally {
    busy.value = false
  }
}

useShortcut('Mod+Enter', () => void confirm())
</script>

<template>
  <PageHeader>
    <template #start>
      <RouterLink to="/import" class="back">← 导入资料</RouterLink>
    </template>
  </PageHeader>
  <div class="wg-page-body layout">
    <main class="main">
      <div class="intro">
        <h1>{{ confirmed ? '已确认' : '确认知识点' }}</h1>
        <p v-if="confirmed">
          来自“{{ draft.sourceName ?? '未命名的资料' }}”，加入了 {{ accepted.length }} 个知识点，没有加入
          {{ draft.items.length - accepted.length }} 个。
        </p>
        <p v-else>
          来自“{{ draft.sourceName ?? '未命名的资料' }}”，{{ questionCount }} 道题拆成了 {{ draft.items.length }}
          个知识点，已经按学习顺序排好。不想学的去掉勾选，确认后加入每日计划。
        </p>
      </div>
      <ul class="items">
        <li v-for="item in draft.items" :key="item.id" class="item" :class="{ off: !isOn(item) }">
          <el-checkbox
            :model-value="isOn(item)"
            :disabled="confirmed"
            :aria-label="item.title"
            @change="(v: string | number | boolean) => toggle(item, v === true)"
          />
          <div class="item-body">
            <div class="item-head">
              <span class="item-title">{{ item.title }}</span>
              <span class="tag">{{ typeLabels[item.type] }}</span>
            </div>
            <p class="summary">{{ item.summary }}</p>
            <p class="meta">
              {{ sources(item) }}
              <span v-if="UNSCHEDULED.includes(item.type)">暂不支持学习，先进知识库，支持后自动加入计划</span>
              <span v-if="confirmed && item.decision === 'REJECTED'">没有加入</span>
            </p>
          </div>
        </li>
      </ul>
    </main>

    <aside class="side">
      <section class="panel summary-panel">
        <p class="big">
          <span class="number">{{ accepted.length }}</span>
          个知识点{{ confirmed ? '已加入' : '将加入' }}
        </p>
        <ul v-if="typeCounts.length > 0" class="types">
          <li v-for="[type, n] in typeCounts" :key="type">
            <span>{{ typeLabels[type] }}{{ UNSCHEDULED.includes(type) ? '（暂不支持）' : '' }}</span>
            <span>{{ n }}</span>
          </li>
        </ul>
        <template v-if="!confirmed">
          <p v-if="daysText" class="days">{{ daysText }}</p>
          <el-button type="primary" class="confirm" :loading="busy" @click="confirm">
            确认加入<kbd>{{ modKeyLabel }} Enter</kbd>
          </el-button>
        </template>
        <RouterLink v-else to="/" class="el-button">回到今天</RouterLink>
      </section>

      <section v-if="!confirmed && draft.uncovered.length > 0" class="panel uncovered">
        <h2>有 {{ draft.uncovered.length }} 道题没有拆出知识点</h2>
        <ul>
          <li v-for="q in draft.uncovered" :key="q.questionId">第 {{ q.questionId }} 题：{{ q.text }}</li>
        </ul>
        <p class="hint">可能是被合并进了别的知识点，也可能是漏了。可以单独再抽一次。</p>
        <el-button class="re-extract" :disabled="busy" @click="reExtract">
          单独再抽这 {{ draft.uncovered.length }} 道
        </el-button>
      </section>
    </aside>
  </div>
</template>

<style scoped>
.back {
  display: inline-flex;
  align-items: center;
  min-height: 44px;
  color: var(--wg-text-muted);
}

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

.intro {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

h1 {
  font-size: 30px;
}

.intro p {
  color: var(--wg-text-muted);
  font-size: 15px;
}

.items {
  margin: 0;
  padding: 4px 24px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
  list-style: none;
}

.item {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  padding: 18px 0;
  border-bottom: 1px solid var(--wg-border);
}

.item:last-child {
  border-bottom: none;
}

/* 取消勾选的条目变淡 */
.item.off .item-body {
  opacity: 0.45;
}

.item-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.item-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.item-title {
  font-size: 16px;
  font-weight: 700;
}

.tag {
  flex: none;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--wg-tag-bg);
  color: var(--wg-text-muted);
  font-size: 13px;
}

.summary {
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.7;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 16px;
  color: var(--wg-text-muted);
  font-size: 13px;
}

.side {
  flex: 1 1 320px;
  max-width: 400px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.panel {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 22px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.big {
  font-size: 15px;
}

.number {
  margin-right: 6px;
  color: var(--wg-primary);
  font-family: var(--wg-font-serif);
  font-size: 44px;
  font-weight: 700;
  line-height: 1;
}

.types {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0 0 14px;
  border-bottom: 1px solid var(--wg-border);
  list-style: none;
  font-size: 14px;
}

.types li {
  display: flex;
  justify-content: space-between;
  color: var(--wg-text-muted);
}

.days {
  font-size: 14px;
}

.confirm {
  height: 48px;
  font-size: 16px;
}

.confirm kbd {
  margin-left: 12px;
}

/* 提示条的配色（ui.md 第 3 节）：warn-soft 底 */
.uncovered {
  border: none;
  background: var(--wg-warn-soft);
}

.uncovered h2 {
  font-size: 16px;
}

.uncovered ul {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin: 0;
  padding: 0;
  list-style: none;
  font-size: 14px;
}

.hint {
  color: var(--wg-text-muted);
  font-size: 13px;
  line-height: 1.7;
}

.re-extract {
  align-self: flex-start;
  color: var(--wg-warn);
}

@media (max-width: 980px) {
  .side {
    max-width: none;
  }
}

@media (max-width: 600px) {
  .layout {
    padding-top: 20px;
  }

  .items {
    padding: 4px 16px;
  }

  h1 {
    font-size: 26px;
  }
}
</style>
