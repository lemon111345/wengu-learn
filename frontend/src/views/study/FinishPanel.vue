<script setup lang="ts">
// 收尾与评分（ui.md 5.4）：最后几条对话、AI 建议、四个评分按钮、“X月X日再见”、接着问和下一个。
// 评分页是每个知识点的中转站：主线是评分后按 Enter 进入下一个，支线是进入接着问、问完回到这里
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ApiError } from '@/api/http'
import { fetchTodayPlan } from '@/api/plan'
import {
  fetchRatingPreview,
  fetchRatingSuggestion,
  rate,
  type RatingOption,
  type RatingSuggestionView,
  type SessionView,
} from '@/api/sessions'
import type { Rating } from '@/api/types'
import { refreshStudyProgress } from '@/layouts/studyHeader'
import { useShortcut } from '@/shortcuts/useShortcut'
import { daysLater, formatMonthDay } from '@/utils/date'
import { ratingColors, ratingLabels } from '@/utils/labels'
import ChatThread from './ChatThread.vue'

const props = defineProps<{ session: SessionView }>()
const router = useRouter()

const RATINGS: Rating[] = ['AGAIN', 'HARD', 'GOOD', 'EASY']

// ---- 最后几条对话：要求完整作答的那句、完整作答、收尾点评 ----
const SHOWN = 3
const messages = computed(() => props.session.messages)
const collapsed = computed(() => Math.max(0, messages.value.length - SHOWN))
/** 完整作答：最后一次“要求完整作答”之后学生的那条 */
const userLabels = computed(() => {
  let restate = messages.value.length - 1
  while (restate >= 0 && messages.value[restate].marker !== 'RESTATE') {
    restate--
  }
  const answer = restate >= 0 ? messages.value[restate + 1] : undefined
  return answer?.role === 'USER' ? { [answer.id]: '你的完整作答' } : {}
})

// ---- AI 建议：第一次要等模型十几到二十几秒；评分不等它 ----
const suggestion = ref<RatingSuggestionView | null>(null)
const suggestionError = ref<string | null>(null)

async function loadSuggestion() {
  suggestionError.value = null
  try {
    suggestion.value = await fetchRatingSuggestion(props.session.id)
  } catch (e) {
    suggestionError.value = e instanceof ApiError ? e.message : 'AI 建议没有生成出来，可以重试，也可以直接评分'
  }
}

// ---- 评分 ----
const preview = ref<RatingOption[]>([])
/** 已经选定的评分和下次复习日期；刷新时来自会话上已评的分 */
const chosen = ref<RatingOption | null>(props.session.rating)
/** 后台保存中：先显示预览中的日期（乐观更新），保存成功后换成后端返回的 */
const saving = ref(false)
/** 第一次评分时播放日期浮上来的动效；刷新后看到的、改分时都不播放 */
const justChosen = ref(false)
const rated = computed(() => chosen.value !== null && !saving.value)
/** 已经评过分、又不能再改（之后又复习过，或者是早期的记录）：能不能改由后端判断（决策 039） */
const locked = props.session.rating !== null && !props.session.ratingEditable
/** 后端已经保存的评分，保存失败时退回到这里 */
let saved = props.session.rating
/** 用户最后点的一档 */
let latest: Rating | null = saved?.rating ?? null

/** 大字的日期。隔几天后改成低的一档时，到期时间可能已经过去，这时写“今天”，不写过去的日期 */
const seeYou = computed(() => {
  const option = chosen.value
  if (!option) {
    return ''
  }
  return !locked && option.days <= 0 ? '今天再见' : `${formatMonthDay(option.date)}再见`
})

onMounted(async () => {
  void loadSuggestion()
  if (!locked) {
    try {
      preview.value = await fetchRatingPreview(props.session.id)
    } catch {
      // 预览取不到时按钮下面不写间隔，照样能评分
    }
  }
})

function optionOf(rating: Rating) {
  return preview.value.find((o) => o.rating === rating)
}

/** 选一档或改成另一档：立刻显示，后台保存（乐观更新） */
function choose(rating: Rating) {
  if (locked || rating === latest) {
    return
  }
  latest = rating
  chosen.value = optionOf(rating) ?? null
  if (saved === null) {
    justChosen.value = true
  }
  // 已经有请求在路上时不再发，由正在跑的 save 接着处理
  if (!saving.value) {
    void save()
  }
}

/**
 * 同一时间只发一个请求，保证后端按顺序收到。连续快速改分时，中间点过的不发，
 * 等前一个返回后只发最后一次的选择；以最后一次为准。
 */
async function save() {
  saving.value = true
  try {
    while (latest !== null && latest !== saved?.rating) {
      const sending: Rating = latest
      saved = await rate(props.session.id, sending)
      // 等待期间又改了的话，这个结果已经过时，不显示
      if (latest === sending) {
        chosen.value = saved
      }
      // 评过分或改了分，当天进度可能变了
      refreshStudyProgress()
    }
  } catch (e) {
    // 保存失败：退回上一次保存成功的评分，提示重试（决策 021）
    latest = saved?.rating ?? null
    chosen.value = saved
    if (saved === null) {
      justChosen.value = false
    }
    ElMessage.error(e instanceof ApiError ? e.message : '评分没有保存，请重试')
  } finally {
    saving.value = false
  }
}

// ---- 下一个、接着问 ----
async function next() {
  if (!rated.value) {
    return
  }
  try {
    // 重新取今天的计划；最后一个评完后，这一步也会记下“当天完成”（决策 026）
    const plan = await fetchTodayPlan()
    const item = plan.reviews[0] ?? plan.newItems[0]
    await router.push(item ? `/study/${item.kpId}` : '/')
  } catch {
    await router.push('/')
  }
}

function followUp() {
  void router.push(`/session/${props.session.id}/follow-up`)
}

RATINGS.forEach((rating, i) => useShortcut(String(i + 1), () => void choose(rating)))
useShortcut('Enter', () => void next())
</script>

<template>
  <div class="finish">
    <ChatThread
      :messages="messages"
      :collapsed="collapsed"
      :collapsed-text="`前面的 ${collapsed} 条对话已折叠`"
      :user-labels="userLabels"
    />

    <section class="rating-panel">
      <h2>这次学得怎么样？</h2>
      <div class="suggestion" aria-live="polite">
        <template v-if="suggestion">
          <p>
            <strong>AI 建议：{{ ratingLabels[suggestion.rating] }}。</strong>{{ suggestion.analysis }}
          </p>
          <p v-if="suggestion.weakPoints.length > 0" class="weak">
            没掌握的：{{ suggestion.weakPoints.join('；') }}。下次复习会重点问这里。
          </p>
        </template>
        <p v-else-if="suggestionError" class="weak">
          {{ suggestionError }}
          <el-button text type="primary" @click="loadSuggestion">重试</el-button>
        </p>
        <p v-else class="weak">AI 正在看你的回答……</p>
      </div>

      <div class="ratings" role="group" aria-label="评分">
        <button
          v-for="(rating, i) in RATINGS"
          :key="rating"
          type="button"
          class="rating"
          :class="{ picked: chosen?.rating === rating }"
          :disabled="locked && chosen?.rating !== rating"
          :aria-pressed="chosen?.rating === rating"
          @click="choose(rating)"
        >
          <kbd class="key">{{ i + 1 }}</kbd>
          <span class="name">
            <span class="dot" :style="{ background: ratingColors[rating] }" />{{ ratingLabels[rating] }}
          </span>
          <span class="interval">{{ optionOf(rating) ? daysLater(optionOf(rating)!.days) : '' }}</span>
          <span v-if="suggestion?.rating === rating" class="ai">AI 建议</span>
        </button>
      </div>
      <p v-if="!chosen" class="hint">按 1～4 选择，评分后会算出下次复习的日期</p>
      <p v-else-if="locked" class="hint">这次评分不能再改了：之后又复习过，或者是早期的记录</p>
    </section>

    <div v-if="chosen" class="see-you" :class="{ animate: justChosen }">
      <span class="date">{{ seeYou }}</span>
      <p>到时会换个角度问，重点问这次没掌握的地方。</p>
    </div>

    <div class="actions">
      <el-button @click="followUp">接着问</el-button>
      <el-button type="primary" class="next" :disabled="!rated" @click="next">下一个<kbd>Enter</kbd></el-button>
    </div>
  </div>
</template>

<style scoped>
.finish {
  display: flex;
  flex-direction: column;
  gap: 32px;
}

.rating-panel {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 24px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

h2 {
  font-size: 18px;
}

.suggestion {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 14px 18px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
  font-size: 14px;
  line-height: 1.75;
}

.weak {
  color: var(--wg-text-muted);
}

.ratings {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.rating {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 4px;
  min-height: 92px;
  padding: 12px 6px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-card);
  background: var(--wg-bg);
  color: var(--wg-text);
  font: inherit;
  cursor: pointer;
}

.rating:hover:not(:disabled) {
  border-color: var(--wg-border-strong);
}

.rating.picked {
  border-color: var(--wg-primary);
  box-shadow: inset 0 0 0 1px var(--wg-primary);
  background: var(--wg-primary-soft);
}

.rating:disabled {
  cursor: default;
  opacity: 0.55;
}

.key {
  position: absolute;
  top: 8px;
  left: 8px;
}

.name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 16px;
  font-weight: 700;
}

.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.interval {
  min-height: 1.5em;
  color: var(--wg-text-muted);
  font-size: 14px;
}

.ai {
  color: var(--wg-primary);
  font-size: 12px;
  font-weight: 700;
}

.hint {
  color: var(--wg-text-muted);
  font-size: 13px;
}

.see-you {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.date {
  color: var(--wg-primary);
  font-family: var(--wg-font-serif);
  font-size: 44px;
  font-weight: 700;
  line-height: 1.3;
}

.see-you p {
  color: var(--wg-text-muted);
  font-size: 14px;
}

/* 本产品唯一的一处动效：选中后日期浮上来（ui.md 5.4）；用户设置了减少动效时不播放 */
.see-you.animate {
  animation: float-up 0.45s ease-out;
}

@keyframes float-up {
  from {
    opacity: 0;
    transform: translateY(16px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .see-you.animate {
    animation: none;
  }
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 12px;
}

.next {
  height: 48px;
  padding: 0 22px;
  font-size: 16px;
}

.next kbd {
  margin-left: 12px;
}

.actions .el-button:first-child {
  height: 48px;
}

@media (max-width: 600px) {
  .ratings {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .date {
    font-size: 34px;
  }
}
</style>
