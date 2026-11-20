<script setup lang="ts">
// 评分页的学习缺口（ui.md 5.4，决策 053）：勾选后加入知识库，后台抽取完自动确认，这里轮询草稿显示结果。
// 和评分无关，单独成一个组件：只接收会话 id 和评分建议，处理完把新的评分建议交还给评分页
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { fetchDraft, retryDraft, type DraftView } from '@/api/drafts'
import { ApiError } from '@/api/http'
import { addGaps, type RatingSuggestionView } from '@/api/sessions'
import { gapResults, type GapOutcome } from './gaps'

const props = defineProps<{ sessionId: number; suggestion: RatingSuggestionView }>()
const emit = defineEmits<{ updated: [suggestion: RatingSuggestionView] }>()

/** 轮询间隔，和导入页一样 */
const POLL_INTERVAL = 3000
/** 草稿 READY 却一直没确认（自动确认出错）时，最多再查几次就停下，提示去“等你确认” */
const MAX_READY_POLLS = 10

/** 还没处理时勾了哪几条（下标） */
const checked = ref(new Set<number>())
const submitting = ref(false)
const draft = ref<DraftView | null>(null)
const retrying = ref(false)
/** 草稿已经整理好、却没有自动加入 */
const stuck = ref(false)
let readyPolls = 0
let timer: ReturnType<typeof setTimeout> | null = null

const processed = computed(() => props.suggestion.gapPicks !== null)
const results = computed(() =>
  gapResults(props.suggestion.outOfScopeGaps, props.suggestion.gapPicks, draft.value),
)
const status = computed(() => draft.value?.status ?? null)
const failureReason = computed(
  () => draft.value?.batches.find((b) => b.status === 'FAILED')?.failureReason ?? '原因不明，可以重试',
)

function toggle(index: number, value: boolean) {
  const next = new Set(checked.value)
  if (value) {
    next.add(index)
  } else {
    next.delete(index)
  }
  checked.value = next
}

async function add() {
  if (submitting.value || checked.value.size === 0) {
    return
  }
  submitting.value = true
  try {
    emit('updated', await addGaps(props.sessionId, [...checked.value].sort((a, b) => a - b)))
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '没有加入成功，请重试')
  } finally {
    submitting.value = false
  }
}

/** 查一次草稿：整理中继续查；READY 是自动确认还没做完，多等几次；确认或失败后停下 */
async function poll() {
  stopPolling()
  const draftId = props.suggestion.gapDraftId
  if (draftId === null) {
    return
  }
  try {
    draft.value = await fetchDraft(draftId)
  } catch {
    // 网络问题等：下一轮再查
  }
  const current = draft.value?.status
  if (current === 'READY') {
    readyPolls++
    stuck.value = readyPolls >= MAX_READY_POLLS
  }
  if (current === undefined || current === 'RUNNING' || (current === 'READY' && !stuck.value)) {
    timer = setTimeout(poll, POLL_INTERVAL)
  }
}

function stopPolling() {
  if (timer !== null) {
    clearTimeout(timer)
    timer = null
  }
}

async function retry() {
  const draftId = props.suggestion.gapDraftId
  if (draftId === null || retrying.value) {
    return
  }
  retrying.value = true
  try {
    draft.value = await retryDraft(draftId)
    readyPolls = 0
    stuck.value = false
    void poll()
  } catch (e) {
    ElMessage.error(e instanceof ApiError ? e.message : '没有重试成功，请再试一次')
  } finally {
    retrying.value = false
  }
}

/** 结果的说明；有标题的（已加入、知识库里已有）标题逐行列在下面：标题都以问号结尾，连成一句很难读 */
function outcomeLabel(outcome: GapOutcome): string {
  switch (outcome.kind) {
    case 'added':
      return outcome.titles.length > 1 ? `已加入 ${outcome.titles.length} 个知识点：` : '已加入：'
    case 'existing':
      return '知识库里已有：'
    case 'none':
      return '没有整理出单独的知识点'
    case 'skipped':
      return '没有加入'
    default:
      return ''
  }
}

function outcomeTitles(outcome: GapOutcome): string[] {
  return outcome.kind === 'added' || outcome.kind === 'existing' ? outcome.titles : []
}

// 刚加入时 gapDraftId 从无到有，开始轮询；刷新或回到这个页面时，挂载后接着查
watch(
  () => props.suggestion.gapDraftId,
  () => void poll(),
)
onMounted(() => void poll())
onBeforeUnmount(stopPolling)
</script>

<template>
  <section class="gaps" aria-labelledby="gaps-title">
    <h2 id="gaps-title">这次暴露的缺口，要不要加入知识库？</h2>
    <p class="hint">超出这道题范围、你还不会的内容。勾选的会整理成知识点，排在待学的最前面</p>

    <ul class="list">
      <li
        v-for="(result, index) in results"
        :key="index"
        :class="{ picked: processed && result.picked, muted: processed && !result.picked }"
      >
        <el-checkbox
          :model-value="processed ? result.picked : checked.has(index)"
          :disabled="processed || submitting"
          @change="(v: string | number | boolean) => toggle(index, v === true)"
        >
          {{ result.text }}
        </el-checkbox>
        <div v-if="processed && outcomeLabel(result.outcome)" class="outcome" :class="result.outcome.kind">
          {{ outcomeLabel(result.outcome) }}
          <ul v-if="outcomeTitles(result.outcome).length > 0">
            <li v-for="title in outcomeTitles(result.outcome)" :key="title">{{ title }}</li>
          </ul>
        </div>
      </li>
    </ul>

    <div class="footer" aria-live="polite">
      <el-button v-if="!processed" :disabled="checked.size === 0" :loading="submitting" @click="add">
        加入知识库
      </el-button>
      <p v-else-if="status === 'FAILED'" class="failed">
        整理失败：{{ failureReason }}
        <el-button text type="primary" :loading="retrying" @click="retry">重试</el-button>
      </p>
      <p v-else-if="stuck" class="note">已经整理好，但没有自动加入，请到今天页的“等你确认”里确认</p>
      <p v-else-if="status !== 'CONFIRMED'" class="note">正在整理 {{ suggestion.gapPicks!.length }} 条…</p>
    </div>
  </section>
</template>

<style scoped>
.gaps {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 24px;
  border: 1px solid var(--wg-border);
  border-radius: var(--wg-radius-panel);
}

h2 {
  font-size: 18px;
}

.hint,
.note {
  color: var(--wg-text-muted);
  font-size: 14px;
}

.list {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

/* 缺口是一整句问题，允许换行，不让复选框把文字挤成一行省略 */
.list :deep(.el-checkbox) {
  height: auto;
  align-items: flex-start;
  white-space: normal;
}

.list :deep(.el-checkbox__label) {
  line-height: 1.6;
}

.list :deep(.el-checkbox__input) {
  margin-top: 4px;
}

/* 处理完后复选框都禁用了；勾过的文字保持正常颜色，和没勾的（变淡）分得开 */
.picked :deep(.el-checkbox.is-disabled .el-checkbox__label) {
  color: var(--wg-text);
}

.muted {
  opacity: 0.55;
}

.outcome {
  margin: 2px 0 0 24px;
  color: var(--wg-text-muted);
  font-size: 14px;
  line-height: 1.6;
}

.outcome ul {
  margin: 2px 0 0;
  padding-left: 18px;
}

.outcome.added {
  color: var(--wg-success);
}

.failed {
  color: var(--wg-warn);
  font-size: 14px;
}
</style>
