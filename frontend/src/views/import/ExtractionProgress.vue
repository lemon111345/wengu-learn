<script setup lang="ts">
// 抽取进度（ui.md 5.7 第 3 步）：每批一个进度块，失败的可以重试；抽完后点“查看结果”进入确认
import { computed } from 'vue'
import type { BatchView, DraftView } from '@/api/drafts'

const props = defineProps<{ draft: DraftView; retrying: boolean }>()
const emit = defineEmits<{ retry: []; viewResult: [] }>()

/** 每道题大约 4 秒：30 题一批约 2 分钟，和决策 013 实测的“1～3 分钟”一致。批次按顺序执行 */
const SECONDS_PER_QUESTION = 4

const totalQuestions = computed(() => props.draft.batches.reduce((n, b) => n + b.questionIds.length, 0))
const unfinished = computed(() => props.draft.batches.filter((b) => b.status === 'PENDING' || b.status === 'RUNNING'))
const failed = computed(() => props.draft.batches.filter((b) => b.status === 'FAILED'))
const minutesLeft = computed(() => {
  const questions = unfinished.value.reduce((n, b) => n + b.questionIds.length, 0)
  return Math.max(1, Math.ceil((questions * SECONDS_PER_QUESTION) / 60))
})

const title = computed(() => {
  if (unfinished.value.length > 0) {
    return '正在抽取知识点'
  }
  return failed.value.length > 0 ? '有的批次没抽出来' : '抽取完成'
})

const description = computed(() => {
  if (unfinished.value.length > 0) {
    return `${totalQuestions.value} 道题分成 ${props.draft.batches.length} 批处理，大约还要 ${minutesLeft.value} 分钟。可以先离开，完成后会出现在今天页的“等你确认”里。`
  }
  if (failed.value.length > 0) {
    return `${failed.value.length} 批没抽出来，可以重试；已经抽出来的会保留。`
  }
  return `${totalQuestions.value} 道题拆成了 ${props.draft.items.length} 个知识点。`
})

/** 题号连续时写“第 1～30 题”，否则写“30 道题” */
function range(batch: BatchView) {
  const ids = batch.questionIds
  const contiguous = ids.every((id, i) => i === 0 || id === ids[i - 1] + 1)
  return contiguous && ids.length > 1 ? `第 ${ids[0]}～${ids[ids.length - 1]} 题` : `${ids.length} 道题`
}

/** 一批抽出的知识点数：条目不会跨批，按第一个来源题号归到批次 */
function itemCount(batch: BatchView) {
  return props.draft.items.filter((i) => batch.questionIds.includes(i.sourceQuestionIds[0])).length
}

function statusText(batch: BatchView) {
  switch (batch.status) {
    case 'READY':
      return `完成，抽出 ${itemCount(batch)} 个知识点`
    case 'RUNNING':
      return '正在抽取'
    case 'PENDING':
      return '排队中'
    default:
      return '这一批没抽出来'
  }
}
</script>

<template>
  <section class="panel">
    <div class="head">
      <h2>{{ title }}</h2>
      <p class="hint" aria-live="polite">{{ description }}</p>
    </div>
    <ol class="batches">
      <li v-for="batch in draft.batches" :key="batch.no" class="batch" :class="batch.status.toLowerCase()">
        <div class="batch-line">
          <span class="batch-name">
            <span v-if="batch.status === 'READY'" class="check" aria-hidden="true">✓</span>
            第 {{ batch.no }} 批：{{ range(batch) }}
          </span>
          <span class="batch-status">{{ statusText(batch) }}</span>
        </div>
        <span class="bar" aria-hidden="true"><span class="fill" /></span>
        <!-- 失败原因（ui.md 第 7 节、决策 032），如 Key 不对、超时；原因不明时不显示 -->
        <p v-if="batch.status === 'FAILED' && batch.failureReason" class="reason">{{ batch.failureReason }}</p>
      </li>
    </ol>
    <div class="actions">
      <RouterLink to="/" class="el-button">回到今天</RouterLink>
      <el-button v-if="failed.length > 0 && unfinished.length === 0" type="primary" :loading="retrying" @click="emit('retry')">
        重试失败的批次（{{ failed.length }} 批）
      </el-button>
      <el-button v-else type="primary" :disabled="draft.status !== 'READY'" @click="emit('viewResult')">
        {{ draft.status === 'READY' ? '查看结果' : '完成后查看结果' }}
      </el-button>
    </div>
  </section>
</template>

<style scoped>
.panel {
  display: flex;
  flex-direction: column;
  gap: 20px;
  padding: 28px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

.head {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

h2 {
  font-size: 20px;
}

.hint {
  color: var(--wg-text-muted);
  font-size: 14px;
}

.batches {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.batch {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px 20px;
  border-radius: var(--wg-radius-card);
  background: var(--wg-surface);
}

.batch-line {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 4px 12px;
  font-size: 15px;
}

.batch-name {
  font-weight: 700;
}

.check {
  margin-right: 6px;
  color: var(--wg-primary);
}

.batch-status {
  color: var(--wg-text-muted);
  font-size: 14px;
}

.reason {
  color: var(--wg-text-muted);
  font-size: 13px;
  line-height: 1.6;
}

.failed .batch-status {
  color: var(--wg-warn);
}

.bar {
  position: relative;
  height: 4px;
  overflow: hidden;
  border-radius: 2px;
  background: var(--wg-border);
}

.fill {
  position: absolute;
  inset: 0 auto 0 0;
  width: 0;
  border-radius: 2px;
  background: var(--wg-primary);
}

.ready .fill {
  width: 100%;
}

/* 进行中的批次不知道进度，显示来回移动的一段（回应用户的操作，不算装饰性动效） */
.running .fill {
  width: 40%;
  background: var(--wg-heat-3);
  animation: indeterminate 1.4s ease-in-out infinite;
}

@keyframes indeterminate {
  from {
    left: -40%;
  }
  to {
    left: 100%;
  }
}

@media (prefers-reduced-motion: reduce) {
  .running .fill {
    left: 0;
    animation: none;
  }
}

.failed .bar {
  background: var(--wg-warn-soft);
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.actions .el-button {
  height: 44px;
}

@media (max-width: 600px) {
  .panel {
    padding: 20px;
  }
}
</style>
